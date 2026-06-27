#!/system/bin/sh
# Install Intel houdini (arm/arm64 translation) into /system on a rooted
# Genymotion Android 11 x86_64 device. Run on-device as root:
#   adb push houdini_system.tar /data/local/tmp/houdini.tar
#   adb push houdini_flash.sh   /data/local/tmp/houdini_flash.sh
#   adb shell su -c 'sh /data/local/tmp/houdini_flash.sh'
#   adb reboot
set -e
TAR=/data/local/tmp/houdini.tar

echo "[*] remount / rw"
mount -o rw,remount /

echo "[*] extracting houdini + arm/arm64 system libs into /system"
cd /
tar -xpf "$TAR"

echo "[*] appending ABI + native-bridge (houdini) props to /system/build.prop"
cat >> /system/build.prop <<'EOF'
ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi,x86_64,x86
ro.product.cpu.abilist32=armeabi-v7a,armeabi,x86
ro.product.cpu.abilist64=arm64-v8a,x86_64
ro.system.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi,x86_64,x86
ro.system.product.cpu.abilist32=armeabi-v7a,armeabi,x86
ro.system.product.cpu.abilist64=arm64-v8a,x86_64
ro.odm.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi,x86_64,x86
ro.odm.product.cpu.abilist32=armeabi-v7a,armeabi,x86
ro.odm.product.cpu.abilist64=arm64-v8a,x86_64
ro.dalvik.vm.native.bridge=libhoudini.so
ro.enable.native.bridge.exec=1
ro.enable.native.bridge.exec64=1
ro.dalvik.vm.isa.arm=x86
ro.dalvik.vm.isa.arm64=x86_64
EOF

echo "[*] appending vendor ABI props to /system/vendor/build.prop"
cat >> /system/vendor/build.prop <<'EOF'
ro.vendor.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi,x86_64,x86
ro.vendor.product.cpu.abilist32=armeabi-v7a,armeabi,x86
ro.vendor.product.cpu.abilist64=arm64-v8a,x86_64
EOF

# houdini ELF exec registration (for arm/arm64 standalone binaries); .so loading
# uses the native.bridge prop above and does not require binfmt.
echo ':arm_exe:M::\x7fELF\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\x28::/system/bin/houdini:P' > /proc/sys/fs/binfmt_misc/register 2>/dev/null || true
echo ':arm64_exe:M::\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7::/system/bin/houdini64:P' > /proc/sys/fs/binfmt_misc/register 2>/dev/null || true

sync
echo "[*] done. Reboot, then verify:"
echo "    adb shell getprop ro.product.cpu.abilist        (must contain arm64-v8a)"
echo "    adb shell getprop ro.dalvik.vm.native.bridge     (must be libhoudini.so)"
