import struct

P = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_raw.json"
raw = open(P, "rb").read()

def varint(buf, i):
    r = 0; s = 0; n = len(buf)
    while i < n:
        b = buf[i]; i += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80):
            return r, i
        s += 7
    raise ValueError("eof")

def walk(buf, pad="", label="", maxn=200):
    i = 0
    k = 0
    while i < len(buf) and k < maxn:
        tag, i = varint(buf, i)
        f, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = varint(buf, i)
            print("%s%s f%d varint %d" % (pad, label, f, v))
        elif wt == 1:
            print("%s%s f%d double %r" % (pad, label, f, struct.unpack("<d", buf[i:i+8])[0])); i += 8
        elif wt == 5:
            print("%s%s f%d float %r" % (pad, label, f, struct.unpack("<f", buf[i:i+4])[0])); i += 4
        elif wt == 2:
            ln, i = varint(buf, i)
            blob = buf[i:i+ln]; i += ln
            try:
                s = blob.decode("utf-8")
                ok = all(32 <= ord(c) < 127 for c in s)
            except Exception:
                ok = False
            if ok:
                print("%s%s f%d str %r" % (pad, label, f, s))
            else:
                print("%s%s f%d bytes len=%d hex=%s" % (pad, label, f, ln, blob.hex()))
        else:
            print("%s%s f%d wt=%d UNKNOWN" % (pad, label, f, wt)); return
        k += 1

# threads blob
i = 0
top = {}
while i < len(raw):
    tag, i = varint(raw, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(raw, i)
    blob = raw[i:i+ln]; i += ln
    top.setdefault(f, []).append(blob)

tn = top[2][0]
print("### ThreadNode root:")
i = 0
kids = []
while i < len(tn):
    tag, i = varint(tn, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(tn, i)
    blob = tn[i:i+ln]; i += ln
    if f == 1:
        print("  name =", blob.decode())
    else:
        kids.append(blob)
print("  children count:", len(kids))
print()
print("### child[0] full hex:")
c = kids[0]
print(c.hex())
walk(c, "   ")
print()
print("### child[2] (org.lwjgl.Sys.getTime0) full hex:")
print(kids[2].hex())
walk(kids[2], "   ")
print()
print("### a deep child (the 350-byte one):")
big = [k for k in kids if len(k) > 300][0]
walk(big, "   ", maxn=8)
print()
print("### top-level field 6:")
walk(top[6][0], "   ")
print()
print("### top-level field 7[0]:")
walk(top[7][0], "   ")
