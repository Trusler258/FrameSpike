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
TREFS = []
for k, v in tn.get(5, []):
    TREFS += pv(v)

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
NW = 3
parent = [-1] * N
for i, n in enumerate(nodes):
    for r in n['refs']:
        if 0 <= r < N:
            parent[r] = i

selfw = []
for i, n in enumerate(nodes):
    sw = list(n['times'])
    for r in n['refs']:
        if 0 <= r < N:
            for j in range(NW):
                sw[j] -= nodes[r]['times'][j]
    selfw.append([max(x, 0.0) for x in sw])

tot_w = [sum(selfw[i][j] for i in range(N)) for j in range(NW)]
TOT = sum(tot_w)

L = []
L.append("TOTAL self = %.0f ms ; per window = %s" % (TOT, ["%.0f" % x for x in tot_w]))
# window durations: w1,w2 = 60s, w3 = remainder
dur = [60.0, 60.0, 2.1]
L.append("per-window self ms per second: %s" % ["%.1f" % (tot_w[j]/dur[j]) for j in range(NW)])

L.append("")
L.append("=== ROOT BRANCHES (top level call tree) ===")
for r in TREFS[:10]:
    n = nodes[r]
    L.append("%9.1f ms %6.2f%%  %s.%s:%d  win=%s" % (sum(n['times']), 100.0*sum(n['times'])/TOT, n['class'], n['method'], n['line'], ["%.0f" % x for x in n['times']]))

def show_children(idx, depth=0, minpct=0.5, maxd=5):
    n = nodes[idx]
    L.append("%s%s.%s:%d  incl=%.1f (%.1f%%)" % ("  "*depth, n['class'][-60:], n['method'], n['line'], sum(n['times']), 100.0*sum(n['times'])/TOT))
    if depth >= maxd: return
    kids = sorted([r for r in n['refs'] if 0 <= r < N], key=lambda r: -sum(nodes[r]['times']))
    for r in kids:
        if sum(nodes[r]['times']) < minpct/100.0*TOT: continue
        show_children(r, depth+1, minpct, maxd)

L.append("")
L.append("=== CALL TREE (branches >5% of total) ===")
for r in TREFS:
    if sum(nodes[r]['times']) > 0.05*TOT:
        show_children(r, 0, 5.0, 6)

L.append("")
L.append("=== PER-WINDOW SELF BY CLASS (ms/s, top 30 by total) ===")
cls = collections.defaultdict(lambda: [0.0]*NW)
for i, n in enumerate(nodes):
    for j in range(NW):
        cls[n['class']][j] += selfw[i][j]
rows = sorted(cls.items(), key=lambda kv: -sum(kv[1]))
for c, w in rows[:30]:
    L.append("%9.1f ms (%.2f%%)  ms/s=%s  %s" % (sum(w), 100.0*sum(w)/TOT, ["%.1f" % (w[j]/dur[j]) for j in range(NW)], c))

L.append("")
L.append("=== WHO CALLS THE COSTLY LEAVES ===")
targets = ["nglFinish", "nglDrawArrays", "nSwapBuffers", "nUpdate", "sleep", "nglCallList", "nglBindFramebuffer", "allocateInstance", "yield", "func_174970_a"]
for t in targets:
    idxs = [i for i, n in enumerate(nodes) if n['method'] == t]
    L.append("--- %s: %d nodes, self=%.1f ms" % (t, len(idxs), sum(selfw[i][0]+selfw[i][1]+selfw[i][2] for i in idxs)))
    agg = collections.Counter()
    for i in idxs:
        p = parent[i]
        chain = []
        st_ = sum(selfw[i])
        while p != -1 and len(chain) < 4:
            chain.append("%s.%s:%d" % (nodes[p]['class'].split('.')[-1][:40], nodes[p]['method'], nodes[p]['line']))
            p = parent[p]
        agg[" <- ".join(chain)] += st_
    for k, v in agg.most_common(6):
        L.append("    %8.1f ms  %s" % (v, k))

L.append("")
L.append("=== MOD / 3rd PARTY CLASSES (self time) ===")
mods = collections.defaultdict(float)
modw = collections.defaultdict(lambda: [0.0]*NW)
for i, n in enumerate(nodes):
    c = n['class']
    pkg = None
    for pre in ("xaero.", "gg.essential", "com.replaymod", "net.optifine", "mezz.jei", "io.github", "mynameisjeff",
                "com.lunarclient", "net.minecraftforge", "io.netty", "paulscode", "me.lucko", "com.google", "org.apache",
                "it.unimi", "org.joml", "kotlin", "Config", "vavi", "org.objectweb", "org.cadixdev", "me.jamiemansfield", "net.labymod"):
        if c.startswith(pre):
            pkg = pre
            break
    if pkg:
        mods[pkg] += sum(selfw[i])
        for j in range(NW):
            modw[pkg][j] += selfw[i][j]
for k, v in sorted(mods.items(), key=lambda kv: -kv[1]):
    L.append("%8.1f ms  %6.3f%%  ms/s=%s  %s" % (v, 100.0*v/TOT, ["%.1f" % (modw[k][j]/dur[j]) for j in range(NW)], k))

L.append("")
L.append("=== SEARCH: particle / scoreboard / chat / damage / kill / hit ===")
for kw in ("particle", "effectrenderer", "entityfx", "scoreboard", "guiingame", "guinewchat", "chat", "damage", "kill", "hit", "combo", "reach", "hud", "screenshot", "glFinish", "framebuffer", "shader", "texture"):
    tot = 0.0
    ws = [0.0]*NW
    names = []
    for i, n in enumerate(nodes):
        blob = (n['class'] + "." + n['method']).lower()
        if kw.lower() in blob:
            tot += sum(selfw[i])
            for j in range(NW):
                ws[j] += selfw[i][j]
            names.append(n)
    if tot > 20:
        L.append("-- '%s': self=%.1f ms (%.3f%%) ms/s=%s nodes=%d" % (kw, tot, 100.0*tot/TOT, ["%.1f" % (ws[j]/dur[j]) for j in range(NW)], len(names)))
        for n in sorted(names, key=lambda n: -sum(n['times']))[:4]:
            L.append("      incl=%8.1f  %s.%s:%d" % (sum(n['times']), n['class'], n['method'], n['line']))

L.append("")
L.append("=== io.github / kotlin / misc class names ===")
seen = set()
for n in nodes:
    c = n['class']
    for pre in ("io.github", "kotlin", "Config", "vavi", "mynameisjeff", "com.lunarclient", "org.objectweb", "me.jamiemansfield"):
        if c.startswith(pre) and c not in seen:
            seen.add(c)
            L.append("   %s" % c)
            break

out = "\n".join(L)
open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_deep.txt", "w", encoding="utf-8").write(out)
print("ok")
