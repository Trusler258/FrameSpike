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

def parse(buf):
    out = collections.defaultdict(list)
    i = 0; n = len(buf)
    while i < n:
        tag, i = varint(buf, i)
        f, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = varint(buf, i); out[f].append(("v", v))
        elif wt == 2:
            ln, i = varint(buf, i); blob = buf[i:i+ln]; i += ln; out[f].append(("b", blob))
        elif wt == 1:
            out[f].append(("d", buf[i:i+8])); i += 8
        elif wt == 5:
            out[f].append(("f", buf[i:i+4])); i += 4
        else:
            raise ValueError("bad wt")
    return out

def pv(b):
    r = []; i = 0
    while i < len(b):
        v, i = varint(b, i); r.append(v)
    return r

def pd(b):
    return list(struct.unpack("<%dd" % (len(b)//8), b)) if len(b) % 8 == 0 else []

def st(b):
    return b.decode("utf-8", "replace")

top = parse(raw)
tb = [v for k, v in top[2] if k == 'b'][0]
tn = parse(tb)
nodes_raw = [v for k, v in tn.get(3, [])]
trefs = []
for k, v in tn.get(5, []):
    trefs += pv(v)

nodes = []
for nb in nodes_raw:
    m = parse(nb)
    nd = {'class': st(m[3][0][1]) if 3 in m else '',
          'method': st(m[4][0][1]) if 4 in m else '',
          'line': m[6][0][1] if 6 in m else 0,
          'desc': st(m[7][0][1]) if 7 in m else '',
          'times': [], 'refs': []}
    for k, v in m.get(8, []):
        if k == 'b': nd['times'] += pd(v)
    for k, v in m.get(9, []):
        if k == 'b': nd['refs'] += pv(v)
    nodes.append(nd)

NW = 3
incl = [sum(n['times']) for n in nodes]
win = [sum(n['times'][i] for n in nodes if len(n['times']) > i) for i in range(NW)]

self_t = []
for n in nodes:
    cs = sum(incl[r] for r in n['refs'] if 0 <= r < len(nodes))
    self_t.append(max(sum(n['times']) - cs, 0.0))

TOTAL = sum(self_t)
L = []
L.append("nodes=%d  TOTAL_SELF=%.0f ms" % (len(nodes), TOTAL))
L.append("sum incl per window (double counted): %s" % ["%.1f" % x for x in win])
L.append("")
L.append("=== TOP 60 BY SELF TIME (where the thread actually runs) ===")
agg = collections.Counter()
aggwin = collections.defaultdict(lambda: [0.0]*NW)
for i, n in enumerate(nodes):
    key = (n['class'], n['method'], n['line'])
    agg[key] += self_t[i]
    for j in range(min(NW, len(n['times']))):
        aggwin[key][j] += n['times'][j]
for (c, mth, ln), t in agg.most_common(60):
    L.append("%7.2f ms  %5.2f%%  w=%s  %s.%s:%d" % (t, 100.0*t/TOTAL, ["%.0f" % x for x in aggwin[(c,mth,ln)]], c, mth, ln))

L.append("")
L.append("=== TOP 40 BY INCLUSIVE ===")
for i, n in sorted(enumerate(nodes), key=lambda x: -incl[x[0]])[:40]:
    L.append("%8.1f ms  %6.2f%%  %s.%s:%d  desc=%s" % (incl[i], 100.0*incl[i]/TOTAL, n['class'], n['method'], n['line'], n['desc'][:60]))

L.append("")
L.append("=== SELF TIME BY PACKAGE/OWNER ===")
def owner(c):
    if c.startswith("com.moonsworth.lunar"): return "LUNAR (obf client)"
    if c.startswith("net.minecraft"): return "MINECRAFT vanilla"
    if c.startswith("org.lwjgl"): return "LWJGL"
    if c.startswith("io.netty"): return "NETTY"
    if c.startswith(("java.", "javax.", "jdk.", "sun.", "com.sun.")): return "JDK"
    if c.startswith("net.minecraftforge"): return "FORGE"
    if c.startswith("org.spongepowered"): return "MIXIN"
    return "OTHER: " + c.split(".")[0] + "." + (c.split(".")[1] if "." in c else "")
own = collections.Counter()
ownwin = collections.defaultdict(lambda: [0.0]*NW)
for i, n in enumerate(nodes):
    o = owner(n['class'])
    own[o] += self_t[i]
    for j in range(min(NW, len(n['times']))):
        ownwin[o][j] += n['times'][j]
for o, t in own.most_common(40):
    L.append("%8.1f ms  %6.2f%%  w=%s  %s" % (t, 100.0*t/TOTAL, ["%.0f" % x for x in ownwin[o]], o))

L.append("")
L.append("=== FULL CLASS SELF-TIME (top 25 classes) ===")
cls = collections.Counter()
for i, n in enumerate(nodes):
    cls[n['class']] += self_t[i]
for c, t in cls.most_common(25):
    L.append("%8.1f ms  %6.2f%%  %s" % (t, 100.0*t/TOTAL, c))

out = "\n".join(L)
open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_top.txt", "w", encoding="utf-8").write(out)
print("filtered words check done")
