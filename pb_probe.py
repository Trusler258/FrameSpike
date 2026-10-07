import struct, collections, json

P = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_raw.json"
raw = open(P, "rb").read()

class Bad(Exception):
    pass

def varint(buf, i):
    r = 0; s = 0; n = len(buf)
    while i < n:
        b = buf[i]; i += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80):
            return r, i
        s += 7
        if s > 70:
            raise Bad()
    raise Bad()

def fields(buf):
    i = 0; n = len(buf)
    while i < n:
        tag, i = varint(buf, i)
        f = tag >> 3; wt = tag & 7
        if f == 0 or wt not in (0, 1, 2, 5):
            raise Bad()
        if wt == 0:
            v, i = varint(buf, i); yield f, wt, v
        elif wt == 2:
            ln, i = varint(buf, i)
            if i + ln > n: raise Bad()
            yield f, wt, buf[i:i+ln]; i += ln
        elif wt == 5:
            if i + 4 > n: raise Bad()
            yield f, wt, struct.unpack("<f", buf[i:i+4])[0]; i += 4
        elif wt == 1:
            if i + 8 > n: raise Bad()
            yield f, wt, struct.unpack("<d", buf[i:i+8])[0]; i += 8

def top(buf):
    return list(fields(buf))

# metadata
meta_blob = None
threads_blob = None
extra = {}
for f, wt, v in fields(raw):
    if f == 1: meta_blob = v
    elif f == 2: threads_blob = v
    else: extra.setdefault(f, []).append(v)

def is_text(b):
    try:
        s = b.decode("utf-8")
    except Exception:
        return None
    if all(32 <= ord(c) < 127 or c in "\t" for c in s) and len(s) > 0:
        return s
    return None

# dump one thread node
tn_list = [v for f, wt, v in fields(threads_blob) if f == 1]
print("thread count:", len(tn_list))
tn = tn_list[0]
print("== ThreadNode fields ==")
for f, wt, v in fields(tn):
    if wt == 2:
        t = is_text(v)
        print("  f=%d wt=2 len=%d %s" % (f, len(v), ("str=" + repr(t)) if t and f == 1 else "<bytes>"))
    else:
        print("  f=%d wt=%d %r" % (f, wt, v))

print("== first StackTraceNode full dump ==")
stn = [v for f, wt, v in fields(tn) if f == 3][0]
print("len", len(stn))
def dump(buf, depth=0, maxd=4, label=""):
    pad = "  " * depth
    try:
        fs = list(fields(buf))
    except Bad as e:
        print(pad + "!! undecodable len=%d head=%r" % (len(buf), buf[:40]))
        return
    for f, wt, v in fs:
        if wt == 0:
            print("%sf=%d varint %d" % (pad, f, v))
        elif wt == 1:
            print("%sf=%d double %r" % (pad, f, v))
        elif wt == 5:
            print("%sf=%d float %r" % (pad, f, v))
        else:
            t = is_text(v)
            if t is not None and len(t) < 200:
                print("%sf=%d str %r" % (pad, f, t))
            else:
                print("%sf=%d bytes len=%d head=%r" % (pad, f, len(v), v[:40]))
                if depth < maxd:
                    dump(v, depth + 1, maxd)
dump(stn, 0, 3)
