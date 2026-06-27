#!/usr/bin/env bash
# Host-side runner: installs houdini arm translation onto a Genymotion device.
# Usage:  bash flash_houdini.sh 192.168.56.XXX:5555
set -e
export MSYS_NO_PATHCONV=1
ADB="/c/Program Files/Genymobile/Genymotion/tools/adb.exe"
DEV="${1:?usage: flash_houdini.sh <ip:port>}"

"$ADB" connect "$DEV" >/dev/null 2>&1 || true
echo "[*] device: $("$ADB" -s "$DEV" shell getprop ro.build.version.release) (API $("$ADB" -s "$DEV" shell getprop ro.build.version.sdk)) $("$ADB" -s "$DEV" shell uname -m)"

echo "[*] pushing houdini tarball (~260MB)..."
"$ADB" -s "$DEV" push "C:/Users/user/Tools/houdini_system.tar" /data/local/tmp/houdini.tar
echo "[*] pushing flash script..."
"$ADB" -s "$DEV" push "C:/Users/user/Desktop/blocklegends/patched/houdini_flash.sh" /data/local/tmp/houdini_flash.sh

echo "[*] running flash as root..."
"$ADB" -s "$DEV" shell "su -c 'sh /data/local/tmp/houdini_flash.sh'"

echo "[*] rebooting..."
"$ADB" -s "$DEV" reboot
"$ADB" -s "$DEV" wait-for-device
sleep 8
echo "[*] new ABI list : $("$ADB" -s "$DEV" shell getprop ro.product.cpu.abilist)"
echo "[*] native bridge: $("$ADB" -s "$DEV" shell getprop ro.dalvik.vm.native.bridge)"
echo "[*] If arm64-v8a + libhoudini.so appear, tell Claude to continue (GApps + app install + frida)."
