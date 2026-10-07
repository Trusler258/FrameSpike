import struct, collections

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

def fields(buf):
    i = 0; n = len(buf)
    while i < n:
        tag, i = varint(buf, i)
        f = tag >> 3; wt = tag & 7
        if wt == 0:
            v, i = varint(buf, i); yield f, wt, v
        elif wt == 2:
            ln, i = varint(buf, i)
            yield f, wt, buf[i:i+ln]; i += ln
        elif wt == 1:
            yield f, wt, buf[i:i+8]; i += 8
        elif wt == 5:
            yield f, wt, buf[i:i+4]; i += 4
        else:
            raise ValueError("wt")

i = 0
top = {}
while i < len(raw):
    tag, i = varint(raw, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(raw, i)
    top.setdefault(f, []).append(raw[i:i+ln]); i += ln

tn = top[2][0]
kids = []
i = 0
while i < len(tn):
    tag, i = varint(tn, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(tn, i)
    blob = tn[i:i+ln]; i += ln
    if f != 1:
        kids.append(blob)

print("kids:", len(kids))
fc = collections.Counter()
nested = 0
for k in kids:
    try:
        fs = list(fields(k))
    except ValueError:
        continue
    fns = set()
    for f, wt, v in fs:
        fns.add(f)
        if wt == 2 and isinstance(v, bytes) and f in (1, 2, 5, 9, 10) and len(v) > 4:
            nested += 1
    fc[frozenset(fns)] += 1
for k, v in fc.most_common(10):
    print(sorted(k), v)
print("possible nested fields seen:", nested)

# sum f8 doubles across kids
tot = [0.0, 0.0, 0.0]
mx = [0.0, 0.0, 0.0]
cnt = 0
for k in kids:
    for f, wt, v in fields(k):
        if f == 8 and wt == 2 and len(v) % 8 == 0:
            for j in range(len(v) // 8):
                d = struct.unpack("<d", v[j*8:j*8+8])[0]
                tot[j] += d
                mx[j] = max(mx[j], d)
            cnt += 1
print("nodes with f8:", cnt)
print("sum f8 per window:", tot)
print("max f8 per window:", mx)
