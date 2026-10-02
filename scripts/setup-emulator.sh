#!/usr/bin/env bash
# Creates Android 17 (API 37) foldable AVDs with the published Samsung panel specs:
#   Galaxy_Z_Fold8        cover 5.5" 1248x1972 (16:10) · main 7.6" 2448x1848 (4:3, wider than tall)
#   Galaxy_Z_Fold8_Ultra  cover 6.5" 1080x2520 (21:9)  · main 8.0" 2256x2504
# Each has two physical displays (cover + main), a hinge sensor (closed / Flex Mode / open) and a
# fold feature that Jetpack WindowManager reports at the real hinge position.
# Usage: scripts/setup-emulator.sh [Galaxy_Z_Fold8|Galaxy_Z_Fold8_Ultra]   (default: Fold8)
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"
IMAGE="system-images;android-37.0;google_apis;arm64-v8a"
BOOT="${1:-Galaxy_Z_Fold8}"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "emulator" "platforms;android-37.0" "$IMAGE"

# name  mainW mainH coverW coverH hingeX
make_avd() {
  local name=$1 lw=$2 lh=$3 cw=$4 ch=$5 hx=$6
  echo no | avdmanager create avd -n "$name" -k "$IMAGE" -d pixel_9_pro_fold --force
  local cfg="$HOME/.android/avd/$name.avd/config.ini"
  sed -i '' \
    -e "s/^hw.displayRegion.0.1.height=.*/hw.displayRegion.0.1.height=$ch/" \
    -e "s/^hw.displayRegion.0.1.width=.*/hw.displayRegion.0.1.width=$cw/" \
    -e "s/^hw.lcd.height=.*/hw.lcd.height=$lh/" \
    -e "s/^hw.lcd.width=.*/hw.lcd.width=$lw/" \
    -e "s/^hw.lcd.density=.*/hw.lcd.density=420/" \
    -e "s/^hw.sensor.hinge.areas=.*/hw.sensor.hinge.areas=$hx-0-0-$lh/" \
    -e 's/^hw.ramSize=.*/hw.ramSize=3G/' \
    -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' \
    -e 's/^hw.gpu.mode=.*/hw.gpu.mode=host/' \
    -e 's/^showDeviceFrame=.*/showDeviceFrame=no/' "$cfg"
}
make_avd Galaxy_Z_Fold8       2448 1848 1248 1972 1224
make_avd Galaxy_Z_Fold8_Ultra 2256 2504 1080 2520 1128

emulator -avd "$BOOT" -no-boot-anim >/dev/null 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done

# The image's built-in fold overlay describes a Pixel panel; WindowManager drops it because it
# doesn't span the Samsung panel. Point the fold at the real hinge instead.
if [ "$BOOT" = Galaxy_Z_Fold8_Ultra ]; then FEATURE="fold-[1128,0,1128,2504]"; else FEATURE="fold-[1224,0,1224,1848]"; fi
adb shell settings put global display_features "$FEATURE"
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
echo "Ready ($BOOT). Fold: 'adb emu fold' / 'adb emu unfold'. Flex Mode: rotate 90° then 'adb emu sensor set hinge-angle0 100'."
