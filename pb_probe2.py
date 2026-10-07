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
    raise ValueError("eof varint")

# raw top level: field1 meta, field2 threads
i = 0
while i < len(raw):
    tag, i = varint(raw, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(raw, i)
    blob = raw[i:i+ln]; i += ln
    print("top f=%d wt=%d len=%d head=%r" % (f, wt, ln, blob[:24]))

threads_blob = None
i = 0
while i < len(raw):
    tag, i = varint(raw, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(raw, i)
    blob = raw[i:i+ln]; i += ln
    if f == 2:
        threads_blob = blob

print("threads_blob len", len(threads_blob))
i = 0
first_tn = None
while i < len(threads_blob):
    tag, i = varint(threads_blob, i)
    f, wt = tag >> 3, tag & 7
    ln, i = varint(threads_blob, i)
    blob = threads_blob[i:i+ln]; i += ln
    print("  Threads f=%d wt=%d len=%d head=%r" % (f, wt, ln, blob[:24]))
    if f == 1 and first_tn is None:
        first_tn = blob

print("first_tn len", len(first_tn))
print("hex head", first_tn[:24].hex())
i = 0
w = 0
while i < len(first_tn) and w < 12:
    tag, i = varint(first_tn, i)
    f, wt = tag >> 3, tag & 7
    if wt == 0:
        v, i = varint(first_tn, i)
        print("   TN f=%d varint %d" % (f, v))
    elif wt == 2:
        ln, i = varint(first_tn, i)
        blob = first_tn[i:i+ln]; i += ln
        print("   TN f=%d wt2 len=%d head=%r" % (f, ln, blob[:30]))
    else:
        print("   TN f=%d wt=%d ???" % (f, wt))
        break
    w += 1
print("consumed", i, "of", len(first_tn))
