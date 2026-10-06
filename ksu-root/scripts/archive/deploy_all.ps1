# deploy_all.ps1 - One-click cold-start deployment: temp root (exploit) + KernelSU
#
# ⚙️ 设备适配 (本脚本针对 iQOO Neo9 PD2338C / 内核 5.15.178; 移植到其他设备时修改):
#   - $ADB        : 本机 adb.exe 路径 (每台电脑不同, 必改)
#   - $STextPA    : 设备内核 stext 物理地址 (Neo9 = 0xa8010000; 其他设备需从
#                   boot 镜像/内核分析得出, 与 exploit 的 CHEESE_STEXT_PA 一致)
#   - $ExploitBin : 临时 root exploit 二进制 (不同设备/漏洞需要不同的 exploit)
#   - pushMap     : 推送文件清单 (二进制名可改, 但需与 deploy_ksu.sh 中的路径一致)
#   - kernelsu-vivo.ko / unpatch.ko: 需按目标设备内核重新构建 (见 docs/KERNEL_INTEGRATION.md)
#   - kptr_restrict / allow_shell / sucompat 行为: vivo 特性, 其他厂商可能不同
#
# Usage (from PowerShell):
#   powershell -ExecutionPolicy Bypass -File deploy_all.ps1                # reboot + full deploy
#   powershell -ExecutionPolicy Bypass -File deploy_all.ps1 -SkipReboot    # use current boot
#   powershell -ExecutionPolicy Bypass -File deploy_all.ps1 -SoftRebootReady  # + unpatch + kill rootc
#
# -SoftRebootReady: after KSU is up, also:
#   a) load unpatch.ko  -> restore cap_bprm_creds_from_file (soft-reboot compat)
#   b) kill exploit daemon (rootc) -> system runs KSU only
#   => soft reboot works any time afterwards; root via KSU (su_ksu/tsu) only.
#
# Pipeline:
#   1. adb reboot (optional) + wait boot_completed
#   2. push required binaries to /data/local/tmp (skip if present)
#   3. start exploit_vivo_neo9 (CHEESE_DAEMON=1 + CHEESE_STEXT_PA + CHEESE_PATCH_CAP)
#   4. poll /data/local/tmp/rootd_ready.txt (up to 300s, detects device loss)
#   5. load kernelsu-vivo.ko via rootc -> deploy_ksu.sh (u0 ksud insmod allow_shell=1)
#   6. verify: su_ksu -c id -> expect uid=0(root) u:r:ksu:s0
#   7. (optional) unpatch.ko + kill exploit daemon
#
# NOTE: must load the module within ~5 min of boot (vivo sets kptr_restrict=2 later).
# NOTE: do NOT rerun the exploit while rootd is already ready (crashes device); be patient
#       during the spray phase (up to ~9 min) - the wait loop below never restarts it.

param(
    [switch]$SkipReboot = $false,
    [switch]$NoPush = $false,
    [switch]$SoftRebootReady = $false
)

$ErrorActionPreference = "Continue"

# ---- 设备适配区: 按需修改 ----
$ADB        = "C:\Users\qweio\AppData\Local\Programs\AYA\resources\adb\adb.exe"  # 本机 adb 路径
$STextPA    = "0xa8010000"          # 内核 stext 物理地址 (Neo9; 见 CHEESE_STEXT_PA)
$ExploitBin = "exploit_vivo_neo9"   # exploit 二进制名 (在 neo9-root/exploit/)
# -----------------------------

$adb = $ADB
$root = Split-Path -Parent $PSScriptRoot   # ReSukiSU-port/
$DEV = "/data/local/tmp"

# files to push: source-relative -> device name
$pushMap = @{
    "$root\neo9-root\exploit\exploit_vivo_neo9" = "exploit_vivo_neo9"
    "$root\neo9-root\client\rootc"               = "rootc"
    "$root\neo9-root\client\u0"                  = "u0"
    "$root\userspace\ksud"                       = "ksud"
    "$root\kernel-module\kernelsu-vivo.ko"       = "kernelsu-vivo.ko"
    "$root\userspace\su_ksu"                     = "su_ksu"
    "$root\scripts\deploy_ksu.sh"                = "deploy_ksu.sh"
    "$root\test-modules\unpatch\unpatch.ko"      = "unpatch.ko"
}

function Step($msg) { Write-Host "`n===== $msg =====" -ForegroundColor Cyan }

function Wait-Adb {
    for ($i = 0; $i -lt 30; $i++) {
        $s = & $adb get-state 2>$null
        if ($s -match "device") { return $true }
        Start-Sleep 5
    }
    return $false
}

Step "[1/7] ADB device"
if (-not (Wait-Adb)) { Write-Host "FAIL: no adb device" -ForegroundColor Red; exit 1 }
if ($SkipReboot) {
    Write-Host "skip reboot (current boot, uptime=$(& $adb shell 'cat /proc/uptime' 2>$null))"
} else {
    Write-Host "rebooting..."
    & $adb reboot 2>$null
    Start-Sleep 5
    if (-not (Wait-Adb)) { Write-Host "FAIL: device did not come back" -ForegroundColor Red; exit 1 }
    for ($i = 0; $i -lt 24; $i++) {
        $b = & $adb shell "getprop sys.boot_completed" 2>$null
        if ($b -match "1") { Write-Host "boot completed (uptime=$(& $adb shell 'cat /proc/uptime' 2>$null))"; break }
        Start-Sleep 5
    }
}

Step "[2/7] Push binaries"
if ($NoPush) {
    Write-Host "skip push (-NoPush)"
} else {
    foreach ($src in $pushMap.Keys) {
        $name = $pushMap[$src]
        if (-not (Test-Path $src)) { Write-Host "WARN: missing $src" -ForegroundColor Yellow; continue }
        $remote = "$DEV/$name"
        $cur = & $adb shell "ls -la $remote 2>/dev/null | wc -l" 2>$null
        if ($cur -match "0") {
            & $adb push $src $remote 2>$null | Out-Null
            & $adb shell "chmod 755 $remote" 2>$null | Out-Null
            Write-Host "pushed $name"
        } else {
            Write-Host "exists: $name"
        }
    }
}

Step "[3/7] Clean stale state + start exploit"
& $adb shell "rm -f $DEV/rootd_ready.txt $DEV/rootd_cmd $DEV/rootd_out $DEV/rootd_cmd_gpu $DEV/rootd_out_gpu $DEV/exploit_daemon.log; killall exploit_vivo_neo9 2>/dev/null" 2>$null | Out-Null
& $adb shell "cd $DEV && CHEESE_STEXT_PA=$STextPA CHEESE_DAEMON=1 CHEESE_PATCH_CAP=1 nohup ./$ExploitBin > $DEV/exploit_daemon.log 2>&1 &" 2>$null | Out-Null
Start-Sleep 3
$cnt = & $adb shell "ps -A | grep -c exploit" 2>$null
Write-Host "exploit procs: $cnt"

Step "[4/7] Wait for rootd ready (max 9 min, NO restart)"
$ready = $false
for ($i = 0; $i -lt 54; $i++) {
    Start-Sleep 10
    $s = & $adb get-state 2>$null
    if ($s -notmatch "device") { Write-Host "DEVICE LOST at $($i*10)s - exploit crashed; rerun on fresh boot" -ForegroundColor Red; break }
    $r = & $adb shell "cat $DEV/rootd_ready.txt 2>/dev/null" 2>$null
    if ($r -match "ready") { Write-Host "rootd READY after $($i*10)s" -ForegroundColor Green; $ready = $true; break }
}
if (-not $ready) {
    Write-Host "FAIL: rootd not ready. exploit log tail:" -ForegroundColor Red
    & $adb shell "tail -c 300 $DEV/exploit_daemon.log 2>/dev/null | tr -d '\0'" 2>$null
    exit 1
}

Step "[5/7] Load KernelSU module"
& $adb shell "$DEV/rootc 'sh $DEV/deploy_ksu.sh'" 2>$null
Start-Sleep 2
$mod = & $adb shell "cat /proc/modules 2>/dev/null | grep kernelsu" 2>$null
Write-Host "module: $mod"

Step "[6/7] Verify root"
$id = & $adb shell "$DEV/su_ksu -c id" 2>$null
Write-Host "su_ksu -c id => $id" -ForegroundColor Green
if ($id -notmatch "uid=0") {
    Write-Host "`n*** WARN: root check failed ***" -ForegroundColor Red
    exit 1
}

if ($SoftRebootReady) {
    Step "[7/7] Soft-reboot-ready: unpatch cap_bprm + kill exploit daemon"
    # 7a. load unpatch.ko (restore cap_bprm_creds_from_file via PTE manipulation)
    & $adb shell "$DEV/rootc '$DEV/u0 $DEV/ksud insmod $DEV/unpatch.ko'" 2>$null
    Start-Sleep 2
    $up = & $adb shell "cat /proc/modules 2>/dev/null | grep unpatch" 2>$null
    Write-Host "unpatch module: $up"
    if ($up -notmatch "unpatch") {
        Write-Host "WARN: unpatch.ko not loaded - soft reboot may still fail!" -ForegroundColor Yellow
    }
    # 7b. verify KSU still works after unpatch (independent of exploit caps)
    $id2 = & $adb shell "$DEV/su_ksu -c id" 2>$null
    Write-Host "su after unpatch => $id2" -ForegroundColor Green
    # 7c. kill exploit daemon (rootc gone; system runs KSU only)
    & $adb shell "killall exploit_vivo_neo9 2>/dev/null" 2>$null | Out-Null
    Start-Sleep 2
    $exp = & $adb shell "ps -A 2>/dev/null | grep -c exploit" 2>$null
    Write-Host "exploit procs after kill: $exp"
    $id3 = & $adb shell "$DEV/su_ksu -c id" 2>$null
    Write-Host "su after kill => $id3" -ForegroundColor Green

    Write-Host "`n*** DEPLOY OK: KSU only + soft-reboot ready ***" -ForegroundColor Green
    Write-Host "  root:   adb shell /data/local/tmp/su_ksu -c <cmd>   (Termux: tsu)"
    Write-Host "  soft reboot: safe now (cap_bprm restored; kernelsu survives)"
    Write-Host "  full reboot: rerun this script (with reboot) to redeploy"
} else {
    Write-Host "`n*** DEPLOY OK: KernelSU root available (exploit daemon still running) ***" -ForegroundColor Green
    Write-Host "  add -SoftRebootReady to also unpatch + kill rootc (system runs KSU only)"
}
Write-Host "Hint: adb shell /data/local/tmp/su_ksu -c <cmd> ; Termux: tsu"
