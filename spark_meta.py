import struct, collections, datetime, sys

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
            ln, i = varint(buf, i)
            blob = buf[i:i+ln]; i += ln
            out[f].append(("b", blob))
        elif wt == 1:
            out[f].append(("d", buf[i:i+8])); i += 8
        elif wt == 5:
            out[f].append(("f", buf[i:i+4])); i += 4
        else:
            raise ValueError("bad wt %d at %d" % (wt, i))
    return out

def packed_varints(b):
    r = []; i = 0
    while i < len(b):
        v, i = varint(b, i); r.append(v)
    return r

def packed_doubles(b):
    return list(struct.unpack("<%dd" % (len(b) // 8), b)) if len(b) % 8 == 0 else []

def s(blob):
    return blob.decode("utf-8", "replace")

top = parse(raw)

# ---- metadata ----
md = parse(top[1][0][1])
R = []
R.append("=== SAMPLER METADATA ===")
creator = parse(md[1][0][1])
R.append("creator: %s  uuid=%s  type=%d" % (s(creator[2][0][1]), s(creator[3][0][1]), creator[1][0][1]))
start_ms = md[2][0][1]
R.append("start_time: %d -> %s" % (start_ms, datetime.datetime.fromtimestamp(start_ms/1000)))
if 11 in md:
    end_ms = md[11][0][1]
    R.append("end_time:   %d -> %s  (duration %.1f s)" % (end_ms, datetime.datetime.fromtimestamp(end_ms/1000), (end_ms-start_ms)/1000.0))
R.append("interval(us): %s" % [v for _, v in md.get(3, [])])
R.append("thread_dumper: %s" % [( k, (s(v) if k=='b' else v)) for k, v in md.get(4, [])])
R.append("data_aggregator: %s" % [(k, (s(v) if k=='b' else v)) for k, v in md.get(5, [])])
if 6 in md:
    R.append("comment: %s" % s(md[6][0][1]))
if 12 in md:
    R.append("number_of_ticks: %s" % [v for _, v in md[12]])
for fld, label in ((15, "sampler_mode"), (16, "sampler_engine"), (17, "sampler_engine_version")):
    if fld in md:
        R.append("%s: %s" % (label, [s(v) if k == 'b' else v for k, v in md[fld]]))
if 7 in md:
    pm = parse(md[7][0][1])
    R.append("platform: type=%s name=%s version=%s mc=%s brand=%s spark=%s" % (
        [v for _, v in pm.get(1, [])], s(pm[2][0][1]), s(pm[3][0][1]),
        s(pm[4][0][1]) if 4 in pm else "-",
        s(pm[8][0][1]) if 8 in pm else "-",
        s(pm[9][0][1]) if 9 in pm else "-"))
if 9 in md:
    ss = parse(md[9][0][1])
    cpu = parse(ss[1][0][1]) if 1 in ss else {}
    mem = parse(ss[2][0][1]) if 2 in ss else {}
    os_ = parse(ss[5][0][1]) if 5 in ss else {}
    jvm = parse(ss[9][0][1]) if 9 in ss else {}
    R.append("cpu: model=%s threads=%s proc_usage=%s" % (
        s(cpu[4][0][1]) if 4 in cpu else "-", [v for _, v in cpu.get(1, [])],
        [struct.unpack("<d", v)[0] for k, v in cpu.get(2, []) if k == 'd']))
    if 1 in mem:
        mp = parse(mem[1][0][1])
        R.append("mem physical: used=%s total=%s" % (
            [v for _, v in mp.get(1, [])], [v for _, v in mp.get(2, [])]))
    R.append("os: %s %s %s" % (s(os_[1][0][1]) if 1 in os_ else "", s(os_[2][0][1]) if 2 in os_ else "", s(os_[3][0][1]) if 3 in os_ else ""))
    R.append("jvm: vendor=%s version=%s vendor_version=%s" % (
        s(jvm[1][0][1]) if 1 in jvm else "", s(jvm[2][0][1]) if 2 in jvm else "", s(jvm[3][0][1]) if 3 in jvm else ""))
if 13 in md:
    R.append("--- mods/sources ---")
    for k, v in md[13]:
        pm2 = parse(v)
        R.append("  %s v%s" % (s(pm2[1][0][1]) if 1 in pm2 else "?", s(pm2[2][0][1]) if 2 in pm2 else "?"))

# ---- platform statistics ----
if 8 in md:
    R.append("=== PLATFORM STATISTICS ===")
    ps = parse(md[8][0][1])
    if 1 in ps:
        m = parse(ps[1][0][1])
        heap = parse(m[1][0][1]) if 1 in m else {}
        nh = parse(m[2][0][1]) if 2 in m else {}
        def mu(x):
            return {k: [v for _, v in x.get(k, [])] for k in (1, 2, 3, 4)}
        R.append("heap: %s" % mu(heap))
        R.append("non_heap: %s" % mu(nh))
        for k, v in m.get(3, []):
            pool = parse(v)
            R.append("  pool %s usage=%s" % (s(pool[1][0][1]), mu(parse(pool[2][0][1])) if 2 in pool else {}))
    if 4 in ps:
        t = parse(ps[4][0][1])
        R.append("tps: %s" % {k: [struct.unpack('<d', v)[0] for kk, v in t.get(k, []) if kk == 'd'] for k in (1, 2, 3)})
    if 6 in ps:
        p = parse(ps[6][0][1])
        R.append("ping: %s" % {k: [struct.unpack('<d', v)[0] for kk, v in p.get(k, []) if kk == 'd'] for k in (1, 2, 3)})
    if 7 in ps:
        R.append("player_count: %s" % ps[7][0][1])

# ---- time windows ----
R.append("=== TIME WINDOWS ===")
tw = []
for k, v in top.get(6, []):
    if k == 'b':
        tw = packed_varints(v)
R.append("time_windows (raw): %s" % tw)
if 7 in top:
    for k, v in top[7]:
        ws = parse(v)
        d = {}
        if 1 in ws: d['ticks'] = [x for _, x in ws[1]]
        if 2 in ws: d['cpu_process'] = [struct.unpack('<d', x)[0] for kk, x in ws[2] if kk == 'd']
        if 3 in ws: d['cpu_system'] = [struct.unpack('<d', x)[0] for kk, x in ws[3] if kk == 'd']
        if 4 in ws: d['tps'] = [struct.unpack('<d', x)[0] for kk, x in ws[4] if kk == 'd']
        if 5 in ws: d['mspt_median'] = [struct.unpack('<d', x)[0] for kk, x in ws[5] if kk == 'd']
        if 6 in ws: d['mspt_max'] = [struct.unpack('<d', x)[0] for kk, x in ws[6] if kk == 'd']
        for fld, nm in ((11, 'start_time'), (12, 'end_time'), (13, 'duration')):
            if fld in ws: d[nm] = [x for _, x in ws[fld]]
        R.append("  window key=%s %s" % ([x for _, x in ws[1]] if 1 in ws else "?", {k2: v2 for k2, v2 in d.items() if k2 != 'ticks'}))

# ---- threads ----
threads = [v for k, v in top[2] if k == 'b']
R.append("=== THREADS: %d ===" % len(threads))

ROOT = None
for tb in threads:
    tn = parse(tb)
    name = s(tn[1][0][1])
    nodes_raw = [v for k, v in tn.get(3, [])]
    trefs = []
    for k, v in tn.get(5, []):
        trefs += packed_varints(v)
    R.append("thread '%s': %d flat nodes, %d root refs" % (name, len(nodes_raw), len(trefs)))

    nodes = []
    for nb in nodes_raw:
        m = parse(nb)
        node = {
            'class': s(m[3][0][1]) if 3 in m else "",
            'method': s(m[4][0][1]) if 4 in m else "",
            'line': m[6][0][1] if 6 in m else 0,
            'desc': s(m[7][0][1]) if 7 in m else "",
            'times': [],
            'refs': [],
        }
        for k, v in m.get(8, []):
            if k == 'b':
                node['times'] += packed_doubles(v)
        for k, v in m.get(9, []):
            node['refs'] += packed_varints(v)
        nodes.append(node)
    ROOT = (name, nodes, trefs)

name, nodes, trefs = ROOT
NW = max(len(n['times']) for n in nodes) if nodes else 0
R.append("windows per node: %d" % NW)

tot_incl = [0.0] * NW
for n in nodes:
    for i, t in enumerate(n['times']):
        tot_incl[i] += t

# total sampled time = sum of self times = sum(incl) - sum(children incl)
self_by_node = [0.0] * len(nodes)
for idx, n in enumerate(nodes):
    child_sum = 0.0
    for r in n['refs']:
        if 0 <= r < len(nodes):
            child_sum += sum(nodes[r]['times'])
    self_by_node[idx] = sum(n['times']) - child_sum

total_self = sum(self_by_node)
R.append("sum of INCLUSIVE per window: %s" % ["%.0f" % x for x in tot_incl])
R.append("TOTAL SELF TIME (all samples): %.0f (unit=?)" % total_self)

# root children inclusive
root_incl = sum(sum(nodes[r]['times']) for r in trefs if 0 <= r < len(nodes))
R.append("sum of ROOT children inclusive: %.0f" % root_incl)
R.append("root refs count: %d of %d nodes" % (len(trefs), len(nodes)))

out = "\n".join(R)
open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_meta.txt", "w", encoding="utf-8").write(out)
print(out)
