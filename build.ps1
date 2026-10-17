# Manual APK build, driven from an ASCII-only working root because aapt2
# cannot open directories whose path contains non-ASCII characters.
$ErrorActionPreference = 'Continue'
$root  = $PSScriptRoot

# ASCII scratch root. Override with PACEBUILD_ASCII if you want it elsewhere.
$ascii = $env:PACEBUILD_ASCII
if (-not $ascii) {
    if (Test-Path 'D:\') { $ascii = 'D:\pacebuild' } else { $ascii = (Join-Path $env:SystemDrive 'pacebuild') }
}
$sdkLink = Join-Path $ascii 'sdk'

# Android SDK: ANDROID_SDK_ROOT / ANDROID_HOME, else tools\android-sdk next to this file.
$realSdk = $env:ANDROID_SDK_ROOT
if (-not $realSdk) { $realSdk = $env:ANDROID_HOME }
if (-not $realSdk) { $realSdk = Join-Path $root 'tools\android-sdk' }
if (-not (Test-Path $realSdk)) {
    throw ('Android SDK not found at ' + $realSdk + ' - put it in tools\android-sdk or set ANDROID_SDK_ROOT')
}

if (-not (Test-Path $sdkLink)) {
    New-Item -ItemType Directory -Force -Path $ascii | Out-Null
    New-Item -ItemType Junction -Path $sdkLink -Target $realSdk | Out-Null
}

$bt   = Join-Path $sdkLink 'build-tools\36.0.0'
$plat = Join-Path $sdkLink 'platforms\android-36\android.jar'
$app  = Join-Path $ascii 'app'
$out  = Join-Path $ascii 'build'

# JDK: keep JAVA_HOME if it already points at a JDK, else fall back to the default install.
$javacExe = $null
if ($env:JAVA_HOME) { $javacExe = Join-Path $env:JAVA_HOME 'bin\javac.exe' }
if (-not $javacExe -or -not (Test-Path $javacExe)) {
    $cand = 'C:\Program Files\Java\jdk-17'
    if (Test-Path (Join-Path $cand 'bin\javac.exe')) { $env:JAVA_HOME = $cand }
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    throw 'JDK not found - set JAVA_HOME to a JDK 17 install'
}

$aapt2     = Join-Path $bt 'aapt2.exe'
$d8        = Join-Path $bt 'd8.bat'
$zipalign  = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

# mirror sources into ASCII root
if (Test-Path $app) { Remove-Item $app -Recurse -Force }
Copy-Item (Join-Path $root 'app') $app -Recurse -Force
Copy-Item (Join-Path $root 'pack_dex.py') (Join-Path $ascii 'pack_dex.py') -Force

# Shizuku dependency jars (extracted from the AARs): copy to the ASCII root
# before compiling. Keep this file ASCII-only on purpose: Windows PowerShell
# 5.1 decodes a BOM-less script as ANSI, and a mis-decoded Chinese comment can
# swallow the following line, silently nulling a variable.
$libDst = Join-Path $ascii 'lib'
if (-not (Test-Path -LiteralPath $libDst)) {
    New-Item -ItemType Directory -Force -Path $libDst -ErrorAction SilentlyContinue | Out-Null
}
Copy-Item (Join-Path $root 'tools\shizuku\*.jar') $libDst -Force -ErrorAction SilentlyContinue
$libJars = @(Get-ChildItem $libDst -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$cp = ($plat + ';' + ($libJars -join ';'))
Write-Host ('  shizuku jars: ' + $libJars.Count)

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $out 'gen'),(Join-Path $out 'classes'),(Join-Path $out 'dex') | Out-Null

Write-Host '== 1/6 aapt2 compile =='
& $aapt2 compile --dir (Join-Path $app 'res') -o (Join-Path $out 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '== 2/6 aapt2 link =='
& $aapt2 link -o (Join-Path $out 'base.apk') -I $plat --manifest (Join-Path $app 'AndroidManifest.xml') -R (Join-Path $out 'res.zip') --java (Join-Path $out 'gen') -A (Join-Path $app 'assets') --min-sdk-version 29 --target-sdk-version 33 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== 3/6 javac =='
$srcs = @(Get-ChildItem (Join-Path $app 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$rs   = @(Get-ChildItem (Join-Path $out 'gen') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$javacOut = & javac -nowarn -Xlint:none -source 8 -target 8 -encoding UTF-8 -classpath $cp -d (Join-Path $out 'classes') ($srcs + $rs) 2>&1
# javac notes about deprecated APIs are not errors: quiet on success, shown on failure
if ($LASTEXITCODE -ne 0) { $javacOut | Write-Host; throw 'javac failed' }

Write-Host '== 4/6 d8 =='
& jar cf (Join-Path $out 'classes.jar') -C (Join-Path $out 'classes') .
& $d8 --lib $plat --min-api 29 --output (Join-Path $out 'dex') (@((Join-Path $out 'classes.jar')) + $libJars)
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== 5/6 pack classes.dex =='
& python (Join-Path $ascii 'pack_dex.py')
if ($LASTEXITCODE -ne 0) { throw 'pack failed' }

Write-Host '== 6/6 zipalign + sign =='
& $zipalign -f -p 4 (Join-Path $out 'unsigned.apk') (Join-Path $out 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $ascii 'debug.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' 2>&1 | Out-Null
}
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out (Join-Path $out 'PaceAssist.apk') (Join-Path $out 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

New-Item -ItemType Directory -Force -Path (Join-Path $root 'build') | Out-Null
Copy-Item (Join-Path $out 'PaceAssist.apk') (Join-Path $root 'build\PaceAssist.apk') -Force

Write-Host ''
Write-Host ('BUILD OK -> ' + (Join-Path $root 'build\PaceAssist.apk'))
Get-Item (Join-Path $root 'build\PaceAssist.apk') | Select-Object FullName, Length
