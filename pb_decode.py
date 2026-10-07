import json, sys, os

P = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_raw.json"
raw = open(P, "rb").read()

class Bad(Exception):
    pass

def varint(buf, i):
    r = 0; s = 0
    n = len(buf)
    while i < n:
        b = buf[i]; i += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80):
            return r, i
        s += 7
        if s > 70:
            raise Bad()
    raise Bad()

def decode(buf, depth=0, maxdepth=3, out=None, path=""):
    i = 0
    n = len(buf)
    while i < n:
        tag, i = varint(buf, i)
        f = tag >> 3
        wt = tag & 7
        p = path + "/" + str(f)
        if f == 0 or wt not in (0, 1, 2, 5):
            raise Bad()
        if wt == 0:
            v, i = varint(buf, i)
            out.append((depth, p, "varint", v))
        elif wt == 2:
            ln, i = varint(buf, i)
            if i + ln > n:
                raise Bad()
            data = buf[i:i+ln]; i += ln
            out.append((depth, p, "len=%d" % ln, data[:80]))
            if depth < maxdepth and ln > 0:
                try:
                    decode(data, depth+1, maxdepth, out, p)
                except Bad:
                    pass
        elif wt == 5:
            if i + 4 > n: raise Bad()
            i += 4
            out.append((depth, p, "f32", buf[i-4:i].hex()))
        elif wt == 1:
            if i + 8 > n: raise Bad()
            i += 8
            out.append((depth, p, "f64", buf[i-8:i].hex()))

allout = []
try:
    decode(raw, 0, 4, allout, "")
except Bad:
    pass

lines = []
for d, p, t, v in allout:
    lines.append("%s%s %s %r" % ("  " * d, p, t, v))
open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_tree.txt", "w", encoding="utf-8").write("\n".join(lines))
print("nodes:", len(allout))
print("\n".join(lines[:60]))
