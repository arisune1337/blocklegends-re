#!/usr/bin/env bash
# Host-side runner: installs berberis arm64 translation onto the Genymotion device.
# Run from Git Bash:  bash "C:/Users/user/Desktop/blocklegends/patched/flash_translation.sh"
set -e
export MSYS_NO_PATHCONV=1
ADB="/c/Program Files/Genymobile/Genymotion/tools/adb.exe"
DEV=192.168.56.102:5555

"$ADB" connect "$DEV" >/dev/null 2>&1 || true

echo "[*] pushing translation tarball..."
"$ADB" -s "$DEV" push "C:/Users/user/Tools/libndk_translation.tar" /data/local/tmp/ndk.tar

echo "[*] pushing flash script..."
"$ADB" -s "$DEV" push "C:/Users/user/Desktop/blocklegends/patched/ndk_flash.sh" /data/local/tmp/ndk_flash.sh

echo "[*] running flash as root on device..."
"$ADB" -s "$DEV" shell "su -c 'sh /data/local/tmp/ndk_flash.sh'"

echo "[*] rebooting device..."
"$ADB" -s "$DEV" reboot

echo "[*] waiting for device to come back..."
"$ADB" -s "$DEV" wait-for-device
sleep 8
echo "[*] new ABI list:"
"$ADB" -s "$DEV" shell getprop ro.product.cpu.abilist
echo "[*] native bridge:"
"$ADB" -s "$DEV" shell getprop ro.dalvik.vm.native.bridge
echo "[*] If arm64-v8a appears above, translation is active. Tell Claude to continue with install."
