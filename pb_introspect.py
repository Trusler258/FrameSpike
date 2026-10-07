import json, sys, collections

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
    """yield (fieldno, wiretype, value_or_bytes, raw_slice)"""
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
            yield f, wt, buf[i:i+4]; i += 4
        elif wt == 1:
            if i + 8 > n: raise Bad()
            yield f, wt, buf[i:i+8]; i += 8

# top-level overview
counts = collections.Counter()
lens = collections.defaultdict(list)
try:
    for f, wt, v in fields(raw):
        counts[(f, wt)] += 1
        if wt == 2:
            lens[f].append(len(v))
except Bad as e:
    print("top-level decode ended early")

out = []
for k in sorted(counts):
    out.append("top field %s count=%d  lens(first10)=%s" % (k, counts[k], lens[k[0]][:10]))
print("\n".join(out))

# inspect field 2 and 3 first bytes
for target in (2, 3, 4):
    seen = 0
    try:
        for f, wt, v in fields(raw):
            if f == target and wt == 2:
                print("--- top/%d occurrence %d len=%d head=%r" % (target, seen, len(v), v[:60]))
                try:
                    for g, w2, v2 in fields(v):
                        print("      sub f=%d wt=%d val=%r" % (g, w2, v2 if not isinstance(v2, bytes) else v2[:50]))
                except Bad:
                    print("      (not decodable as message)")
                seen += 1
                if seen >= 3:
                    break
    except Bad:
        pass
