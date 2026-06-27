#!/system/bin/sh
# Flash berberis libndk_translation (arm64->x86_64) into /system on a rooted
# Genymotion Android 12 device. Run on-device as root:
#   adb push libndk_translation.tar /data/local/tmp/ndk.tar
#   adb push ndk_flash.sh           /data/local/tmp/ndk_flash.sh
#   adb shell su -c 'sh /data/local/tmp/ndk_flash.sh'
#   adb reboot
set -e
TAR=/data/local/tmp/ndk.tar

echo "[*] remount / rw"
mount -o rw,remount /

echo "[*] extracting arm64 translation libs into /system tree"
cd /
tar -xpf "$TAR"

echo "[*] appending ABI + native-bridge props to /system/build.prop"
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
ro.dalvik.vm.native.bridge=libndk_translation.so
ro.dalvik.vm.isa.arm64=x86_64
ro.dalvik.vm.isa.arm=x86
ro.enable.native.bridge.exec=1
ro.enable.native.bridge.exec64=1
ro.ndk_translation.version=0.2.3
ro.berberis.version=0.2.3
EOF

echo "[*] appending vendor ABI props to /system/vendor/build.prop"
cat >> /system/vendor/build.prop <<'EOF'
ro.vendor.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi,x86_64,x86
ro.vendor.product.cpu.abilist32=armeabi-v7a,armeabi,x86
ro.vendor.product.cpu.abilist64=arm64-v8a,x86_64
EOF

sync
echo "[*] done. Reboot the device, then verify:"
echo "    adb shell getprop ro.product.cpu.abilist   (must contain arm64-v8a)"
