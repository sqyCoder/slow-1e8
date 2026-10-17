"""把 d8 产出的 classes.dex 塞进 aapt2 link 出来的 base.apk。

不是用 'a' 追加 —— 追加会沿用 aapt2 写下的原始 entry 头，其中大文件带 data
descriptor，后续 zipalign 会报 "W zip : WARNING: header mismatch"。这里整包重写
一遍，让每个 entry 的 local header 与 central directory 完全一致（压缩方式、时间戳
都照抄，resources.arsc 仍是 STORED，后面 zipalign -p 4 才能给它做 4 字节对齐）。
"""
import os, sys, zipfile

root = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(root, 'build')
base = os.path.join(out, 'base.apk')
unsigned = os.path.join(out, 'unsigned.apk')
dex = os.path.join(out, 'dex', 'classes.dex')

if not os.path.exists(base):
    sys.exit('base.apk missing')
if not os.path.exists(dex):
    sys.exit('classes.dex missing')

with zipfile.ZipFile(base) as src, zipfile.ZipFile(unsigned, 'w') as dst:
    for it in src.infolist():
        info = zipfile.ZipInfo(it.filename, date_time=it.date_time)
        info.compress_type = it.compress_type
        info.external_attr = it.external_attr
        info.create_system = it.create_system
        dst.writestr(info, src.read(it.filename))
    dst.write(dex, 'classes.dex')

print('dex packed:', os.path.getsize(unsigned), 'bytes')
