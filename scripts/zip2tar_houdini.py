#!/usr/bin/env python3
# Repackage niizam houdini system.zip -> tar, preserving symlinks & unix modes,
# so it can be extracted on-device with toybox tar (Windows can't make symlinks).
import zipfile, tarfile, io, sys, stat

SRC = r'C:/Users/user/Tools/houdini_system.zip'
DST = r'C:/Users/user/Tools/houdini_system.tar'

zin = zipfile.ZipFile(SRC)
tout = tarfile.open(DST, 'w')
nsym = nfile = ndir = 0
for zi in zin.infolist():
    mode = (zi.external_attr >> 16) & 0xFFFF
    name = zi.filename.rstrip('/')
    if not name:
        continue
    ti = tarfile.TarInfo(name)
    ti.mtime = 0
    if zi.is_dir():
        ti.type = tarfile.DIRTYPE
        ti.mode = 0o755
        tout.addfile(ti); ndir += 1
        continue
    if stat.S_ISLNK(mode):
        target = zin.read(zi).decode('utf-8', 'replace')
        ti.type = tarfile.SYMTYPE
        ti.linkname = target
        ti.mode = 0o777
        tout.addfile(ti); nsym += 1
        continue
    data = zin.read(zi)
    ti.type = tarfile.REGTYPE
    ti.size = len(data)
    ti.mode = (mode & 0o7777) or 0o644
    tout.addfile(ti, io.BytesIO(data)); nfile += 1
tout.close(); zin.close()
print(f'[ok] {DST}  files={nfile} symlinks={nsym} dirs={ndir}')
