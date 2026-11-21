# Jifeng Download Android - manual build (no Gradle)
# Keep this script ASCII-only; the Chinese-named APK copy is produced by tools/copy_apk.py
# so powershell.exe ANSI decoding cannot mangle the destination filename.
# Continue: native tools may write notes/warnings to stderr; nonzero exit codes are
# checked explicitly after each step instead of relying on $ErrorActionPreference.
$ErrorActionPreference = "Continue"

$SDK = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$BT = "$SDK\build-tools\34.0.0"
$ANDROID_JAR = "$SDK\platforms\android-34\android.jar"
$JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot" }
$JAVAC = "$JAVA_HOME\bin\javac.exe"
$JAR = "$JAVA_HOME\bin\jar.exe"
$KEYTOOL = "$JAVA_HOME\bin\keytool.exe"
$PY = (Get-Command py).Source
# 签名口令走环境变量；本地开发默认口令只在本地 dev keystore 上有效。
$KS_PASS = if ($env:JIFENG_KS_PASS) { $env:JIFENG_KS_PASS } else { "dev-only-change-me" }
$KS_DNAME = if ($env:JIFENG_KS_DNAME) { $env:JIFENG_KS_DNAME } else { "CN=Jifeng Download Dev, O=Jifeng, C=CN" }

Set-Location $PSScriptRoot
$BUILD = "$PSScriptRoot\build"
if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Force "$BUILD\classes", "$BUILD\gen", "$BUILD\res", "$BUILD\dex" | Out-Null

$DIST = "$PSScriptRoot\dist"
if (Test-Path $DIST) {
    Get-ChildItem $DIST -Filter *.apk | Remove-Item -Force
} else {
    New-Item -ItemType Directory -Force $DIST | Out-Null
}

Write-Host "1/7 aapt2 compile resources"
& "$BT\aapt2.exe" compile --dir res -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "2/7 aapt2 link"
& "$BT\aapt2.exe" link -o "$BUILD\base.apk" -I $ANDROID_JAR `
    --manifest AndroidManifest.xml -R "$BUILD\res.zip" --java "$BUILD\gen" `
    --auto-add-overlay --min-sdk-version 24 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "3/7 javac"
$sources = Get-ChildItem -Recurse -Filter *.java -Path src, "$BUILD\gen" | ForEach-Object { $_.FullName }
$javacOut = & $JAVAC -J-Xmx256m -encoding UTF-8 -source 8 -target 8 -bootclasspath $ANDROID_JAR -classpath $ANDROID_JAR `
    -d "$BUILD\classes" $sources 2>&1

$javacCode = $LASTEXITCODE
$javacOut | Where-Object { $_ -notmatch "bootstrap class path|source value 8|target value 8|warning|ע:|注:" }
if ($javacCode -ne 0) { throw "javac failed" }

Write-Host "4/7 jar + d8"
& $JAR cf "$BUILD\classes.jar" -C "$BUILD\classes" .
& "$BT\d8.bat" --min-api 24 --lib $ANDROID_JAR --output "$BUILD\dex" "$BUILD\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "5/7 inject classes.dex"
& $PY "$PSScriptRoot\tools\add_dex.py" "$BUILD\base.apk" "$BUILD\dex\classes.dex" "$BUILD\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "dex inject failed" }

Write-Host "6/7 zipalign"
& "$BT\zipalign.exe" -f 4 "$BUILD\unsigned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "7/7 sign"
if (!(Test-Path "$PSScriptRoot\jifeng.keystore")) {
    & $KEYTOOL -genkeypair -keystore "$PSScriptRoot\jifeng.keystore" -alias jifeng `
        -keyalg RSA -keysize 2048 -validity 10000 -storepass $KS_PASS -keypass $KS_PASS `
        -dname $KS_DNAME
}
& "$BT\apksigner.bat" sign --ks "$PSScriptRoot\jifeng.keystore" --ks-key-alias jifeng `
    --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS `
    --out "$DIST\JifengDownload.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

# Chinese-named copy is produced by Python to avoid ANSI mojibake.
& $PY "$PSScriptRoot\tools\copy_apk.py" "$DIST\JifengDownload.apk"
if ($LASTEXITCODE -ne 0) { throw "copy chinese name failed" }

& "$BT\apksigner.bat" verify --print-certs "$DIST\JifengDownload.apk"
$cn = Get-ChildItem $DIST -Filter *.apk | Where-Object { $_.Name -ne "JifengDownload.apk" } | Select-Object -First 1
if ($cn -ne $null) {
    & "$BT\apksigner.bat" verify --print-certs $cn.FullName
}

$a = Get-Item "$DIST\JifengDownload.apk"
$b = if ($cn -ne $null) { Get-Item $cn.FullName } else { $null }
Write-Host ("JifengDownload.apk {0} bytes" -f $a.Length)
if ($b -ne $null) { Write-Host ("chinese copy      {0} bytes" -f $b.Length) }
if ($b -ne $null -and $a.Length -ne $b.Length) { throw "two apk copies differ in size" }
Write-Host "APK build finished"
