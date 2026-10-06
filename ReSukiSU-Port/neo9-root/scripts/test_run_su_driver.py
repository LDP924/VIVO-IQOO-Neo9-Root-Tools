#!/usr/bin/env python3
# test_run_su_driver.py - 离线验证 run_su_until_root.py 的"结局判定"状态机
#
# 为什么需要它: 这个驱动的全部价值在于把三种结局分对 ——
#   命中 / 未命中不重启 / 未命中重启。
# 判错一次的后果是: 把"没命中"当成"已 root"去用, 或者在有 root 时又去重启。
# 真机上一次判定要几分钟且可能重启手机, 所以先用 mock adb 把状态机跑全。
#
# 运行: python3 test_run_su_driver.py
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DRIVER = os.path.join(HERE, 'run_su_until_root.py')

MOCK = r'''#!/usr/bin/env python3
import hashlib, json, os, sys, time
STATE = os.environ['MOCK_STATE']
SCEN = [s for s in os.environ.get('MOCK_SCENARIO', 'hit').split(',') if s]
FAKE_LOG = os.environ['MOCK_LOG']
REBOOT_AFTER = float(os.environ.get('MOCK_REBOOT_AFTER', '4'))
def st():
    try: return json.load(open(STATE))
    except Exception:
        return {'boot_id': 'boot-A', 'launch': 0, 'reboot_at': 0, 'pushed_sha': '', 'reboot_count': 0}
def save(s): json.dump(s, open(STATE, 'w'))
def out(s): sys.stdout.write(s)
argv = sys.argv[1:]
if '-s' in argv:
    i = argv.index('-s'); del argv[i:i+2]
if not argv: sys.exit(1)
s = st(); cmd = argv[0]
if s.get('reboot_at') and time.time() > s['reboot_at'] and s['boot_id'] == 'boot-A':
    s['boot_id'] = 'boot-B'; save(s)
offline = bool(s.get('reboot_at')) and s['reboot_at'] - 1 < time.time() < s['reboot_at'] + 1
if cmd == 'devices': out('List of devices attached\nMOCKSERIAL\tdevice\n'); sys.exit(0)
if cmd == 'get-state':
    out('offline\n' if offline else 'device\n'); sys.exit(1 if offline else 0)
if cmd == 'wait-for-device': sys.exit(0)
if cmd == 'reboot':
    s['reboot_at'] = time.time() + 1; s['reboot_count'] += 1; save(s); out('\n'); sys.exit(0)
if cmd == 'push':
    s['pushed_sha'] = hashlib.sha256(open(argv[1], 'rb').read()).hexdigest(); save(s)
    out('1 file pushed\n'); sys.exit(0)
if cmd == 'shell':
    sc = argv[1]
    # 注意顺序: --su 请求必须先判定, 否则会被下面的 getenforce 等通用分支截胡
    if '--su --ping' in sc: out('PONG\n[rc=0]\n'); sys.exit(0)
    if '--su --id' in sc:
        out('pid=1234 uid=2000 euid=2000 fsuid=0 gid=2000\n'
            'CapEff:\t000001ffffffffff\nSecbits:\t0\n[rc=0]\n'); sys.exit(0)
    if '--su -c' in sc: out('uid=2000(root) gid=2000(root)\nPermissive\nrc=0\n'); sys.exit(0)
    if 'su_ready.txt' in sc:
        out('-rw-rw-rw- 1 root root 41 /data/local/tmp/su_ready.txt\n'
            'srw-rw-rw- 1 root root 0 /data/local/tmp/su.sock\n'); sys.exit(0)
    if 'getprop sys.boot_completed' in sc: out('1\n')
    elif 'boot_id' in sc: out(s['boot_id'] + '\n')
    elif 'uptime' in sc: out('150\n')
    elif 'getenforce' in sc: out('Enforcing\n')
    elif 'MemAvailable' in sc: out('MemAvailable:    9677592 kB\n')
    elif 'sha256sum' in sc: out('%s  /data/local/tmp/x\n' % s.get('pushed_sha', ''))
    elif 'nohup' in sc:
        scen = SCEN[min(s['launch'], len(SCEN) - 1)]; s['launch'] += 1; save(s)
        if scen == 'hit':
            open(FAKE_LOG, 'w').write(
                'phyaddr: 0xfeb00000 (attempt 3/44)\nGPU R/W ready (attempt 3)\n'
                '[stab] 读路径探针: 可用 (stext 首指令 = 0xd503233f)\n'
                'cap_bprm_creds_from_file patched (exec keeps caps, 读回校验通过)\n'
                'root ready (caps mode, uid=2000 euid=2000)\n'
                '[stab] GPU 资源已释放 (context/fd/spray/payload), GPU 硬闸门已落下\n'
                '[su] 内置 su 服务已就绪: /data/local/tmp/su.sock (socket 0666)\n')
        elif scen == 'miss_noreboot':
            open(FAKE_LOG, 'w').write(
                'phyaddr: 0xfc400000 (attempt 44/44)\nSpray failed (attempt 44)\n'
                'All attempts failed\nphyaddr sweep exhausted; reboot and retry\n')
        elif scen == 'miss_reboot':
            open(FAKE_LOG, 'w').write('phyaddr: 0xfeb00000 (attempt 1/44)\nGPU R/W ready (attempt 1)\n')
            s['reboot_at'] = time.time() + REBOOT_AFTER; save(s)
        elif scen == 'hit_binary':
            # 真机日志里混有非 UTF-8 字节 (exploit 内部的二进制/被 tail -c 截断的多字节序列),
            # 严格 utf-8 解码会抛 UnicodeDecodeError 把驱动整个打崩 —— 真实踩过。
            open(FAKE_LOG, 'wb').write(
                'phyaddr: 0xfeb00000 (attempt 5/44)\nGPU R/W ready (attempt 5)\n'
                'root ready (caps mode, uid=2000 euid=2000)\n'
                '[su] 内置 su 服务已就绪: /data/local/tmp/su.sock (socket 0666)\n'.encode()
                + b'\xaa\xfe\x0f\x80 binary-tail \xc3\x28 broken-utf8 \xf0\x9f\x92\xa9\n')
        else:
            open(FAKE_LOG, 'w').write('')
        out('')
    elif 'tail -c' in sc or 'cat /data/local/tmp/su_attempt' in sc:
        # 二进制安全: 逐字节回吐日志 (不做任何解码)
        try:
            with open(FAKE_LOG, 'rb') as f:
                sys.stdout.buffer.write(f.read())
            sys.stdout.buffer.flush()
        except Exception:
            pass
    else: out('')
    sys.exit(0)
sys.exit(0)
'''


def run_case(td, name, scenario, extra_args, want_exit, want_in, want_not_in=None,
             want_in_any=None):
    state = os.path.join(td, 'state.json')
    flog = os.path.join(td, 'log.txt')
    if os.path.exists(state):
        os.remove(state)
    if os.path.exists(flog):
        os.remove(flog)
    env = dict(os.environ,
               MOCK_STATE=state, MOCK_SCENARIO=scenario, MOCK_LOG=flog,
               MOCK_REBOOT_AFTER='3')
    cmd = [sys.executable, DRIVER, '--adb', os.path.join(td, 'adb'),
           '--binary', os.path.join(td, 'fakebin'), '--no-reboot-first'] + extra_args
    # errors='replace': 驱动会把设备端原始字节转发到 stdout, 严格 utf-8 会抛
    # UnicodeDecodeError 让回归测试假失败（run_su_until_root.py 上真实发生过一次）。
    p = subprocess.run(cmd, capture_output=True, text=True,
                       encoding='utf-8', errors='replace', env=env, timeout=180)
    outp = p.stdout + p.stderr
    problems = []
    if want_exit is not None and p.returncode != want_exit:
        problems.append('退出码 %d != 期望 %d' % (p.returncode, want_exit))
    for s in (want_in or []):
        if s not in outp:
            problems.append('缺少输出: %s' % s)
    for s in (want_not_in or []):
        if s in outp:
            problems.append('不该出现: %s' % s)
    if want_in_any:
        if not any(s in outp for s in want_in_any):
            problems.append('缺少任一输出: %s' % want_in_any)
    print('  [%s] %s' % ('OK ' if not problems else 'FAIL', name))
    if problems:
        for x in problems:
            print('        - %s' % x)
        print('        --- 驱动输出尾部 ---')
        for line in outp.splitlines()[-15:]:
            print('        | %s' % line)
    return not problems


def main():
    td = tempfile.mkdtemp(prefix='su_drv_test_')
    try:
        mock = os.path.join(td, 'adb')
        open(mock, 'w').write(MOCK)
        os.chmod(mock, 0o755)
        fb = os.path.join(td, 'fakebin')
        open(fb, 'wb').write(b'\x7fELF' + b'X' * 4096)
        print('== 运行驱动状态机测试 (mock adb) ==')
        ok = True
        ok &= run_case(td, '① 命中 -> 立刻验证 su 并退出 0', 'hit',
                       ['--attempts', '1'], 0,
                       ['命中并完成验证: su 通道可用', '--ping  -> PONG'],
                       ['主动重启以刷新内存布局'])
        ok &= run_case(td, '② 未命中不重启 -> 主动重启后重试 (2 次全灭退出 6)',
                       'miss_noreboot', ['--attempts', '2'], 6,
                       ['结论: MISS_NO_REBOOT', '主动重启以刷新内存布局', '2 次均未命中'],
                       ['命中并完成验证'])
        ok &= run_case(td, '③ 未命中且设备自己重启 -> 识别为 MISS_REBOOT 后重试命中',
                       'miss_reboot,hit', ['--attempts', '2'], 0,
                       ['结论: MISS_REBOOT', '设备在重启, 等待回状态',
                        '命中并完成验证: su 通道可用'])
        ok &= run_case(td, '④ 日志长时间无结论 -> TIMEOUT 不当成命中',
                       'silent', ['--attempts', '1', '--timeout', '6'], 6,
                       ['结论: TIMEOUT'], ['命中并完成验证'])
        ok &= run_case(td, '⑤ 首次尝试未命中, 第二次命中 -> 整体成功',
                       'miss_noreboot,hit', ['--attempts', '3'], 0,
                       ['命中并完成验证: su 通道可用'])
        ok &= run_case(td, '⑥ 日志含非 UTF-8 字节 -> 不崩且仍判定 HIT',
                       'hit_binary', ['--attempts', '1'], 0,
                       ['结论: HIT', '命中并完成验证: su 通道可用',
                        '设备日志尾部'],
                       ['Traceback', 'UnicodeDecodeError'])
        print('=> %s' % ('全部通过' if ok else '有失败项'))
        return 0 if ok else 1
    finally:
        shutil.rmtree(td, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
