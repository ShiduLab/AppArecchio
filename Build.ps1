param(
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Destination
)
$ErrorActionPreference = 'Stop'
$sdkRoot = (Resolve-Path -LiteralPath $Sdk).Path
$buildTools = Join-Path $sdkRoot 'build-tools/35.0.0'
$androidJar = Join-Path $sdkRoot 'platforms/android-35/android.jar'
$destinationPath = (New-Item -ItemType Directory -Force $Destination).FullName
$workspace = (New-Item -ItemType Directory -Force (Join-Path $destinationPath 'build')).FullName
$classes = (New-Item -ItemType Directory -Force (Join-Path $workspace 'classes')).FullName
$dex = (New-Item -ItemType Directory -Force (Join-Path $workspace 'dex')).FullName
$source = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$raw = Join-Path $workspace 'unsigned.apk'
$aligned = Join-Path $workspace 'aligned.apk'
$apk = Join-Path $destinationPath 'AppArecchio.apk'
$key = Join-Path $workspace 'prototype.p12'

function Invoke-Checked {
    param([string]$Program, [string[]]$ArgumentList)
    & $Program @ArgumentList
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE." }
}

Invoke-Checked javac (@('--release','17','-encoding','UTF-8','-classpath',$androidJar,'-d',$classes) + $source)
Invoke-Checked javac @('--release','17','-encoding','UTF-8','-classpath',$classes,'-d',$classes,(Join-Path $PSScriptRoot 'test/DspTest.java'))
Invoke-Checked java @('-cp',$classes,'org.shidulab.apparecchio.DspTest')
Invoke-Checked javac @('--release','17','-encoding','UTF-8','-classpath',$classes,'-d',$classes,(Join-Path $PSScriptRoot 'test/DynamicsTest.java'))
Invoke-Checked java @('-cp',$classes,'org.shidulab.apparecchio.DynamicsTest')
$production = @($source | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) })
$bytecode = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Where-Object {
    ($_.BaseName -split '\$')[0] -in $production
} | ForEach-Object FullName)
Invoke-Checked (Join-Path $buildTools 'd8.bat') (@('--lib',$androidJar,'--min-api','30','--output',$dex) + $bytecode)
$resources = Join-Path $workspace 'resources.zip'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
Invoke-Checked $aapt2 @('compile','--dir',(Join-Path $PSScriptRoot 'res'),'-o',$resources)
Invoke-Checked $aapt2 @('link','-I',$androidJar,'--manifest',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),'-o',$raw,$resources)
Push-Location $dex
try {
    Invoke-Checked (Join-Path $buildTools 'aapt.exe') @('add',$raw,'classes.dex')
} finally { Pop-Location }
Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-f','4',$raw,$aligned)
if (!(Test-Path -LiteralPath $key)) {
    Invoke-Checked keytool @('-genkeypair','-keystore',$key,'-storepass','prototype',
        '-alias','apparecchio','-keyalg','RSA','-keysize','2048','-validity','3650',
        '-dname','CN=AppArecchio Prototype')
}
Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-pass','pass:prototype','--out',$apk,$aligned)
Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @('verify','--verbose',$apk)
