# Start the phone emulator WITH A WINDOW, on the machine that has the screen.
#
# Why this exists
# ---------------
# The build machine has no emulator: /dev/kvm is unreachable from its user
# namespace, and the arm64 escape is closed (the x86_64 emulator rejects a
# foreign architecture outright). So emulators run HERE and are driven from
# there over one SSH reverse forward. See scripts/remote-adb.sh.
#
# That bridge is enough for everything except one thing: signing in to Google
# Play. The bridge account has no interactive desktop, so an emulator it starts
# has nowhere to put a window, and every scheduled task therefore runs with
# -no-window. A Play sign-in needs a human looking at a screen, so it has to be
# launched from a logged-in session -- this one.
#
# The handoff is free. adb is a client/server protocol: the emulator finds the
# adb server on port 5037 by scanning loopback console ports, regardless of
# which account owns either process. The remote worker's tunnel forwards to that
# same server. So an emulator started here is visible there the moment it boots,
# with nothing further to run.
#
#   Run in Windows PowerShell, AS ADMINISTRATOR the first time (it moves the AVD
#   out of the bridge account's profile, which an ordinary user cannot read):
#
#       powershell -ExecutionPolicy Bypass -File C:\projects\start-phone-windowed.ps1
#
$ErrorActionPreference = 'Stop'

$SdkRoot  = 'C:\projects\android\sdk'
$AvdHome  = 'C:\projects\android\avd'
$Emulator = "$SdkRoot\emulator\emulator.exe"
$OldAvd   = 'C:\Users\bfgbridge\.android\avd\bfg_phone.avd'
$NewAvd   = "$AvdHome\bfg_phone.avd"

if (-not (Test-Path $Emulator)) { throw "emulator not found at $Emulator" }

# --- 1. move the AVD somewhere both accounts can reach -----------------------
# It was created by the bridge account and landed in that profile, where this
# session cannot read it. C:\projects is the shared ground both sides already use.
New-Item -ItemType Directory -Force -Path $AvdHome | Out-Null
if (Test-Path $OldAvd) {
    Write-Host "moving bfg_phone.avd out of the bridge account's profile..."
    if (Test-Path $NewAvd) { Remove-Item -Recurse -Force $NewAvd }
    Move-Item -Path $OldAvd -Destination $NewAvd
    Remove-Item 'C:\Users\bfgbridge\.android\avd\bfg_phone.ini' -Force -EA SilentlyContinue
}
if (-not (Test-Path $NewAvd)) { throw "bfg_phone.avd is in neither location" }

# A move within one volume is a rename, so the folder kept the bridge account's
# profile ACLs and this session still could not read it. Reset to whatever
# C:\projects grants, which is what makes the AVD genuinely shared.
& icacls $NewAvd /reset /T /C /Q 2>$null | Out-Null

# The .ini beside the .avd is the pointer the emulator actually resolves.
@"
avd.ini.encoding=UTF-8
path=$NewAvd
path.rel=bfg_phone.avd
target=android-36
"@ | Set-Content -Path "$AvdHome\bfg_phone.ini" -Encoding ASCII

# --- 2. fix the four settings that make a Play sign-in impossible ------------
# The AVD was created headless and its defaults reflect that.
$cfg   = "$NewAvd\config.ini"
$drop  = '^(PlayStore\.enabled|hw\.keyboard|hw\.ramSize|hw\.gpu\.enabled|hw\.gpu\.mode|vm\.heapSize|avd\.id|avd\.name|disk\.dataPartition\.path)='
$lines = @(Get-Content $cfg | Where-Object { $_ -notmatch $drop })
$lines += 'PlayStore.enabled=yes'   # image is google_apis_playstore on a pixel_6, so this is allowed
$lines += 'hw.keyboard=yes'         # otherwise an email address is typed on a touch keyboard
$lines += 'hw.ramSize=4096'         # 2G is not enough to get through sign-in
$lines += 'hw.gpu.enabled=yes'      # it was off; there was no window to draw into
$lines += 'hw.gpu.mode=swiftshader_indirect'
$lines += 'vm.heapSize=512'
$lines += 'avd.id=bfg_phone'
$lines += 'avd.name=bfg_phone'
# disk.dataPartition.path was <temp>, which discards the sign-in on every boot.
# Dropping the line restores the default persistent userdata image.
$lines | Sort-Object | Set-Content $cfg -Encoding ASCII

# --- 3. make sure nothing is already holding the port ------------------------
Get-WmiObject Win32_Process |
    Where-Object { $_.Name -like 'emulator*' -and $_.CommandLine -match 'bfg_phone' } |
    ForEach-Object { Write-Host "stopping headless bfg_phone (pid $($_.ProcessId))"; Stop-Process -Id $_.ProcessId -Force }
schtasks /Change /TN 'bfg-phone-emulator' /DISABLE 2>$null | Out-Null

# --- 4. launch, with a window, in this logged-in session ---------------------
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_HOME     = $SdkRoot
$env:ANDROID_AVD_HOME = $AvdHome

# The emulator keeps scratch state -- feature-flag caches, lock files -- in a
# per-user .android directory, and does NOT create the parent if it is missing.
# It fails with a bare "error: 3" (ERROR_PATH_NOT_FOUND) in an unbounded loop
# that never names the real problem. Every account that has touched the SDK on
# this box so far was the bridge account, so the first launch from a logged-in
# session hits this immediately.
#
# Point it at shared ground instead of the profile, so the next account -- or
# the next worker -- does not rediscover this.
$EmuHome = "$AvdHome\..\emu-home"
New-Item -ItemType Directory -Force -Path $EmuHome | Out-Null
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.android" | Out-Null
$env:ANDROID_EMULATOR_HOME = (Resolve-Path $EmuHome).Path

Write-Host ''
Write-Host 'Starting bfg_phone with a window. First boot after this change is slow'
Write-Host '(the data partition is being recreated) -- give it a few minutes.'
Write-Host ''
Write-Host 'When it is up: open Play Store, sign in, install "Wear OS by Google".'
Write-Host 'The remote worker sees this emulator automatically; nothing to hand over.'
Write-Host ''

& $Emulator -avd bfg_phone -no-snapshot -no-boot-anim
