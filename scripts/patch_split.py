#!/usr/bin/env python3
# Patch minSdkVersion in a split APK's binary AndroidManifest.xml (AXML) to a
# target value, preserving every other zip entry's compression (STORED .so stay
# STORED). Output apk is NOT aligned/signed -- run zipalign -p + apksigner after.
import sys, struct, zipfile, io

MIN_SDK_RESID = 0x0101020c
NEW_MIN_SDK   = int(sys.argv[3]) if len(sys.argv) > 3 else 28

def patch_axml(buf: bytes, new_val: int) -> bytes:
    b = bytearray(buf)
    assert struct.unpack_from('<H', b, 0)[0] == 0x0003, 'not AXML'
    n = len(b)
    off = 8
    res_map_idx = None
    # first pass: locate resource map, find index whose resid == minSdk
    p = 8
    while p + 8 <= n:
        ctype, chdr, csize = struct.unpack_from('<HHI', b, p)
        if csize == 0: break
        if ctype == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            count = (csize - 8) // 4
            for i in range(count):
                rid = struct.unpack_from('<I', b, p + 8 + i*4)[0]
                if rid == MIN_SDK_RESID:
                    res_map_idx = i
                    break
        p += csize
    if res_map_idx is None:
        raise RuntimeError('minSdkVersion resource id not found in resource map')
    # second pass: walk START_ELEMENT chunks, patch matching attribute data
    patched = 0
    p = 8
    while p + 8 <= n:
        ctype, chdr, csize = struct.unpack_from('<HHI', b, p)
        if csize == 0: break
        if ctype == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            attr_start = struct.unpack_from('<H', b, p + 24)[0]
            attr_count = struct.unpack_from('<H', b, p + 28)[0]
            base = p + 16 + attr_start
            for i in range(attr_count):
                ap = base + i*20
                name_idx = struct.unpack_from('<I', b, ap + 4)[0]
                if name_idx == res_map_idx:
                    # Res_value at ap+8: size(2) res0(1) dataType(1) data(4)
                    struct.pack_into('<I', b, ap + 16, new_val)
                    patched += 1
        p += csize
    if patched == 0:
        raise RuntimeError('no minSdkVersion attribute found to patch')
    return bytes(b)

def main():
    src, dst = sys.argv[1], sys.argv[2]
    zin = zipfile.ZipFile(src, 'r')
    with zipfile.ZipFile(dst, 'w') as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == 'AndroidManifest.xml':
                data = patch_axml(data, NEW_MIN_SDK)
                print(f'  patched minSdk -> {NEW_MIN_SDK} in {src}')
            # preserve original compression type per entry (STORED stays STORED)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)
    zin.close()
    print(f'  wrote {dst}')

if __name__ == '__main__':
    main()
