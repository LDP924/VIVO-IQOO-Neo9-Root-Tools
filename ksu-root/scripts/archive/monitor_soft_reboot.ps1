# monitor_soft_reboot.ps1 - Monitor device state across a soft reboot (or full reboot)
# Samples every 10s: adb state, uptime, kernelsu module, rootd, exploit procs, boot_completed
# Usage: powershell -ExecutionPolicy Bypass -File monitor_soft_reboot.ps1 [-Minutes 15] [-Out monitor.log]
#
# ⚙️ 设备适配:
#   - $ADB : 本机 adb.exe 路径 (每台电脑不同, 必改)
#   - $Out : 日志输出路径 (默认写到工程包 scripts/ 下, 可改)
#   - 监视内容: kernelsu 模块名 / rootd_ready.txt / exploit 进程名按实际部署约定,
#     改名时同步修改下方 grep 关键字即可

param([int]$Minutes = 15, [string]$Out = "D:\payload-dumper-go\ReSukiSU-port\scripts\soft_reboot_monitor.log")

# ---- 设备适配区 ----
$ADB = "C:\Users\qweio\AppData\Local\Programs\AYA\resources\adb\adb.exe"
# --------------------

$adb = $ADB
$deadline = (Get-Date).AddMinutes($Minutes)
$sample = 0

"[$(Get-Date -Format HH:mm:ss)] monitor started, sampling every 10s for $Minutes min" | Tee-Object -FilePath $Out -Append

while ((Get-Date) -lt $deadline) {
    $sample++
    $state = (& $adb get-state 2>$null | Out-String).Trim()
    $line = "[$(Get-Date -Format HH:mm:ss)] s=$sample state=$state"
    if ($state -match "device") {
        $upt = (& $adb shell "cat /proc/uptime 2>/dev/null" 2>$null | Out-String).Trim()
        $mod = (& $adb shell "cat /proc/modules 2>/dev/null | grep kernelsu" 2>$null | Out-String).Trim()
        $rdy = (& $adb shell "cat /data/local/tmp/rootd_ready.txt 2>/dev/null" 2>$null | Out-String).Trim()
        $exp = (& $adb shell "ps -A 2>/dev/null | grep -c exploit" 2>$null | Out-String).Trim()
        $bc  = (& $adb shell "getprop sys.boot_completed 2>/dev/null" 2>$null | Out-String).Trim()
        $line += " | upt=$upt | ksu=$mod | rootd=$rdy | expl=$exp | boot=$bc"
    }
    $line | Tee-Object -FilePath $Out -Append
    Start-Sleep 10
}
"[$(Get-Date -Format HH:mm:ss)] monitor finished" | Tee-Object -FilePath $Out -Append
