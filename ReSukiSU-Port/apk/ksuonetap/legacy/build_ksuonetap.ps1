# build_ksuonetap.ps1 - 构建 KSUOneTap APK (shizuku 驱动提权)
# 依赖: D:\android-sdk (build-tools 34.0.0, platform-34), JDK 21, shizuku-api.aar
$ErrorActionPreference = "Stop"
$sdk   = "D:\android-sdk"
$bt    = "$sdk\build-tools\34.0.0"
$plat  = "$sdk\platforms\android-34\android.jar"
$proj  = "D:\payload-dumper-go\ksu-apk\ksuonetap"
$out   = "D:\payload-dumper-go\ksu-apk\out-onetap"
$shizukuJar = "D:\payload-dumper-go\ksu-apk\shizuku-aar\unpacked\classes.jar"
$shizukuAidl = "D:\payload-dumper-go\ksu-apk\shizuku-aidl-un\un\classes.jar"
$shizukuProv = "D:\payload-dumper-go\ksu-apk\shizuku-provider-un\un\classes.jar"
New-Item -ItemType Directory -Force -Path "$out\obj", "$out\gen", "$out\classes" | Out-Null

# 1. aapt2 compile res
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$out\obj\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
# 2. aapt2 link (显式 min/target sdk; vivo 对 minSdk=1 的 app 有兼容模式问题)
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $plat --manifest "$proj\AndroidManifest.xml" `
    --min-sdk-version 28 --target-sdk-version 34 `
    --java "$out\gen" "$out\obj\res.zip" -A "$proj\assets" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
# 3. javac (纯反射, 不依赖 shizuku jar)
$src = Get-ChildItem "$proj\src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& javac -source 8 -target 8 -Xlint:-options -classpath "$plat" -d "$out\classes" `
    "$out\gen\com\neoroot\ksuonetap\R.java" $src 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
# 4. d8 -> dex (把 shizuku-api jar + aidl stub + provider 的 class 并入, 注解不影响运行)
$tmp = "$out\shizuku-cls"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Push-Location $tmp
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\jar.exe" xf $shizukuJar 2>&1 | Out-Null
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\jar.exe" xf $shizukuAidl 2>&1 | Out-Null
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\jar.exe" xf $shizukuProv 2>&1 | Out-Null
Pop-Location
Copy-Item "$tmp\rikka" "$out\classes\" -Recurse -Force
Copy-Item "$tmp\moe" "$out\classes\" -Recurse -Force
$classes = Get-ChildItem "$out\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --lib $plat --output "$out\obj" --min-api 30 $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
# 5. 打包
Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\obj\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()
# 6. zipalign + sign
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if (-not (Test-Path "$out\ksuonetap.keystore")) {
    $oldEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & keytool -genkeypair -keystore "$out\ksuonetap.keystore" -alias ksu -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass ksupass -keypass ksupass -dname "CN=KSUOneTap" -noprompt 2>&1 | Out-Null
    $ErrorActionPreference = $oldEAP
    if (-not (Test-Path "$out\ksuonetap.keystore")) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks "$out\ksuonetap.keystore" --ks-pass pass:ksupass --key-pass pass:ksupass `
    --out "$out\KSUOneTap.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Output "BUILD OK: $out\KSUOneTap.apk"
