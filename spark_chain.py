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
tn = parse([v for k, v in top[2] if k == 'b'][0])
nodes_raw = [v for k, v in tn.get(3, [])]
nodes = []
for nb in nodes_raw:
    m = parse(nb)
    t = []
    for k, v in m.get(8, []):
        if k == 'b': t += pd(v)
    while len(t) < 3: t.append(0.0)
    rf = []
    for k, v in m.get(9, []):
        if k == 'b': rf += pv(v)
    nodes.append({'class': st(m[3][0][1]) if 3 in m else '',
                  'method': st(m[4][0][1]) if 4 in m else '',
                  'line': m[6][0][1] if 6 in m else 0,
                  'times': t, 'refs': rf})
N = len(nodes)
parent = [-1]*N
for i, n in enumerate(nodes):
    for r in n['refs']:
        if 0 <= r < N: parent[r] = i
self_t = []
for i, n in enumerate(nodes):
    cs = sum(sum(nodes[r]['times']) for r in n['refs'] if 0 <= r < N)
    self_t.append(max(sum(n['times']) - cs, 0.0))
TOT = sum(self_t)

L = []
L.append("=== TOP 30 NON-GL SELF NODES + CALLER CHAIN ===")
order = sorted(range(N), key=lambda i: -self_t[i])
shown = 0
for i in order:
    c = nodes[i]['class']
    if c.startswith("org.lwjgl"): continue
    if self_t[i] < 150: break
    chain = []
    p = parent[i]
    while p != -1 and len(chain) < 5:
        chain.append("%s.%s:%d" % (nodes[p]['class'], nodes[p]['method'], nodes[p]['line']))
        p = parent[p]
    L.append("%8.1f ms  self=%6.2f%%  %s.%s:%d" % (self_t[i], 100.0*self_t[i]/TOT, c, nodes[i]['method'], nodes[i]['line']))
    for j, cc in enumerate(chain):
        L.append("        %s^ %s" % ("  "*j, cc))
    shown += 1
    if shown >= 30: break

L.append("")
L.append("=== ALL CLASSES IN SPECIFIC PACKAGES ===")
pkgs = ("xaero.", "io.github.", "net.optifine.shaders", "com.replaymod", "gg.essential", "mezz.jei", "Improved", "Framebuffer")
seen = set()
for i, n in enumerate(nodes):
    c = n['class']
    for p in pkgs:
        if p in c and c not in seen:
            seen.add(c)
            L.append("   %8.1f ms  %s.%s:%d" % (self_t[i], c, n['method'], n['line']))

out = "\n".join(L)
open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_chain.txt", "w", encoding="utf-8").write(out)
print("ok")
