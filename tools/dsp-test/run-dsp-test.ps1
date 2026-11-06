# ============================================================================
#  Geluidstest van de Faust-DSP op de pc.
#
#  Gebruik (vanuit de projectmap):   powershell -File tools\dsp-test\run-dsp-test.ps1
#
#  Wat het doet:
#   1. Faust zet app/src/main/faust/voice.dsp om naar C++ (zelfde opties als de app).
#   2. De clang-compiler uit de Android-NDK bouwt daarmee een Windows-testprogramma
#      (de Windows-compiler zelf loopt vast op de grote wavetable-opbouw).
#   3. De linker van Visual Studio maakt er een .exe van, die meteen draait.
#  Tijdelijke bestanden komen in tools/dsp-test/build/ (niet in git).
#  Geeft exitcode 0 als alles OK is, anders 1.
# ============================================================================
$ErrorActionPreference = 'Stop'

$root  = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$build = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force $build | Out-Null

# --- Gereedschap zoeken -------------------------------------------------------
$props = @{}
$lp = Join-Path $root 'local.properties'
if (Test-Path $lp) {
    foreach ($line in Get-Content $lp) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') {
            $props[$Matches[1]] = $Matches[2].Replace('\:', ':').Replace('\\', '\')
        }
    }
}
$faust = if ($props['faust.exe']) { $props['faust.exe'] } else { 'C:\Program Files\Faust\bin\faust.exe' }
if (-not (Test-Path $faust)) { throw "Faust niet gevonden: $faust" }

$sdk = if ($props['sdk.dir']) { $props['sdk.dir'] } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndk = Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name | Select-Object -Last 1
if (-not $ndk) { throw "Geen Android-NDK gevonden in $sdk\ndk" }
$clang = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'

$vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
$vsPath = $null
if (Test-Path $vswhere) { $vsPath = & $vswhere -latest -products * -property installationPath }
if (-not $vsPath) {
    $vsPath = Get-ChildItem 'C:\Program Files\Microsoft Visual Studio\*\*' -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'VC\Auxiliary\Build\vcvars64.bat') } |
        Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $vsPath) { throw 'Visual Studio (C++-onderdelen) niet gevonden; nodig voor de linker.' }
$vcvars = Join-Path $vsPath 'VC\Auxiliary\Build\vcvars64.bat'

# --- 1. Faust ------------------------------------------------------------------
$dsp = Join-Path $root 'app\src\main\faust\voice.dsp'
Write-Host "Faust: $dsp"
& $faust -lang cpp -cn SynthVoice -single -ftz 2 $dsp -o (Join-Path $build 'SynthVoice.h')
if ($LASTEXITCODE -ne 0) { throw 'Faust-compilatie mislukt' }

# --- 2 + 3. Compileren en koppelen --------------------------------------------
$src = Join-Path $PSScriptRoot 'dsp_test.cpp'
$inc = Join-Path $root 'app\src\main\cpp'
$obj = Join-Path $build 'dsp_test.o'
$exe = Join-Path $build 'dsp_test.exe'
if (Test-Path $exe) { Remove-Item $exe }

# vcvars64 zet de paden naar de Windows-headers en -bibliotheken klaar (voor clang én link).
# Een bat-bestand met spaties in het pad moet via 'call' en een extra paar aanhalingstekens.
$bat = Join-Path $build 'build.bat'
@"
@echo off
call "$vcvars" >nul 2>nul || exit /b 1
"$clang" --target=x86_64-pc-windows-msvc -O2 -std=c++17 "-I$inc" "-I$build" -c "$src" -o "$obj" || exit /b 2
link /nologo "$obj" libcmt.lib libvcruntime.lib libucrt.lib kernel32.lib /out:"$exe" || exit /b 3
"@ | Set-Content -Encoding ascii $bat
cmd /c "`"$bat`""
switch ($LASTEXITCODE) {
    0 { }
    1 { throw 'Visual Studio-omgeving (vcvars64) laden mislukt' }
    2 { throw 'Compileren mislukt' }
    default { throw 'Koppelen mislukt' }
}

# --- Testen --------------------------------------------------------------------
Write-Host ''
& $exe (Join-Path $root 'app\src\main\assets\wavetables.bin')
exit $LASTEXITCODE
