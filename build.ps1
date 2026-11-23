param(
    [switch]$Install
)
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tools = if ($env:FLIPCLOCK_TOOLS) {
    (Resolve-Path $env:FLIPCLOCK_TOOLS).Path
} else {
    (Resolve-Path (Join-Path $Here "..\..\04_工具链\tools")).Path
}
$JdkBin = if (Test-Path "$Tools\bin\javac.exe") { "$Tools\bin" } else { "$Tools\jdk-17.0.2\bin" }

# 读取本地签名配置（优先环境变量，其次 keystore.properties，不向仓库硬编码密码）
$KsFile = $env:FLIPCLOCK_KS
$KsPass = $env:FLIPCLOCK_KS_PASS
$KeyAlias = $env:FLIPCLOCK_KEY_ALIAS
$KeyPass = $env:FLIPCLOCK_KEY_PASS
$PropsPath = Join-Path $Here "keystore.properties"
if (Test-Path $PropsPath) {
    Get-Content $PropsPath | ForEach-Object {
        if ($_ -match '^\s*([^#=]+?)\s*=\s*(.+?)\s*$') {
            $k = $Matches[1]; $v = $Matches[2]
            if ($k -eq "storeFile" -and !$KsFile) { $KsFile = $v }
            elseif ($k -eq "storePassword" -and !$KsPass) { $KsPass = $v }
            elseif ($k -eq "keyAlias" -and !$KeyAlias) { $KeyAlias = $v }
            elseif ($k -eq "keyPassword" -and !$KeyPass) { $KeyPass = $v }
        }
    }
}
if (!$KsFile) { throw "Missing keystore config: create keystore.properties (see keystore.properties.example) or set FLIPCLOCK_KS env var." }
if (![System.IO.Path]::IsPathRooted($KsFile)) { $KsFile = (Resolve-Path (Join-Path $Here $KsFile)).Path }
if (!$KeyAlias) { $KeyAlias = "flipclock" }

$ManifestText = Get-Content -Raw -Encoding UTF8 (Join-Path $Here "app\src\main\AndroidManifest.xml")
$Pkg = if ($ManifestText -match 'package="([^"]+)"') { $Matches[1] } else { "io.github.aragamiz.madrid2flip" }
$VerCode = if ($ManifestText -match 'android:versionCode="([^"]+)"') { $Matches[1] } else { "0" }
$Ver = if ($ManifestText -match 'android:versionName="([^"]+)"') { $Matches[1] } else { "unknown" }

$ReleaseTag = "$VerCode-$Ver"
$ReleaseDir = Join-Path $Here ("release\" + $ReleaseTag)
New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null
$OutApk = Join-Path $ReleaseDir ("${Pkg}_v${Ver}.apk")
$WorkDir = Join-Path $env:TEMP "madridsubscreen_pub_stage"

if (Test-Path $WorkDir) { Remove-Item -Recurse -Force $WorkDir }
New-Item -ItemType Directory -Path "$WorkDir\gen","$WorkDir\classes","$WorkDir\dex" -Force | Out-Null

try {
    Copy-Item -Recurse "$Here\app" "$WorkDir\app" -Force
    Copy-Item "$Tools\framework-res.apk" "$WorkDir\framework-res.apk" -Force
    Copy-Item "$Tools\android-all.jar" "$WorkDir\android-all.jar" -Force
    Copy-Item "$Tools\api-82.jar" "$WorkDir\api-82.jar" -Force
    Copy-Item $KsFile "$WorkDir\release.keystore" -Force

    Push-Location $WorkDir
    & "$Tools\build-tools\aapt2.exe" compile --dir "app/src/main/res" -o "res.zip"
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

    & "$Tools\build-tools\aapt2.exe" link -o "base.apk" -I "framework-res.apk" `
        --manifest "app/src/main/AndroidManifest.xml" -R "res.zip" -A "app/src/main/assets" `
        --java "gen" --min-sdk-version 29 --target-sdk-version 34 --auto-add-overlay
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

    $JavaFiles = (Get-ChildItem -Recurse -Filter *.java "app/src/main/java","gen").FullName
    & "$JdkBin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
        -cp "android-all.jar;api-82.jar" -d "classes" $JavaFiles
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    $ClassFiles = (Get-ChildItem -Recurse -Filter *.class "classes").FullName
    & "$JdkBin\java.exe" -cp "$Tools\build-tools\lib\d8.jar" com.android.tools.r8.D8 `
        --lib "android-all.jar" --min-api 29 --output "dex" $ClassFiles
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    python -c "
import zipfile, shutil
shutil.copy('base.apk', 'unsigned.apk')
with zipfile.ZipFile('unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('dex/classes.dex', 'classes.dex')
"
    if ($LASTEXITCODE -ne 0) { throw "zip package failed" }

    & "$Tools\build-tools\zipalign.exe" -p -f 4 "unsigned.apk" "aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    & "$JdkBin\java.exe" -jar "$Tools\build-tools\lib\apksigner.jar" sign `
        --ks "release.keystore" --ks-pass "pass:$KsPass" --key-pass "pass:$KeyPass" `
        --ks-key-alias $KeyAlias --out "signed.apk" "aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

    Copy-Item "signed.apk" $OutApk -Force
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $WorkDir -ErrorAction SilentlyContinue
}

Write-Host "BUILD_OK $((Get-Item $OutApk).Length) bytes -> $OutApk (Tag: $ReleaseTag)"

if ($Install) {
    & "$Tools\adb\adb.exe" install -r --no-incremental $OutApk
}
