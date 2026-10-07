import re, collections, datetime, os

LOG = r"C:\Users\Huang\.lunarclient\profiles\1.8\logs\latest.log"
txt = open(LOG, "rb").read().decode("utf-8", "replace")
lines = txt.splitlines()
L = []
L.append("lines: %d" % len(lines))

ts_re = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")
def secs(m):
    return int(m.group(1))*3600 + int(m.group(2))*60 + int(m.group(3))

pauses = []   # (sec, kind, ms, line)
netty = []
glerr = []
chat = []
gc = collections.Counter()
for idx, ln in enumerate(lines):
    m = ts_re.match(ln)
    t = secs(m) if m else None
    if "Pause - " in ln:
        mm = re.search(r"Pause - (GC|Unknown) - (\d+)ms", ln)
        if mm and t is not None:
            pauses.append((t, mm.group(1), int(mm.group(2)), idx))
    if "ReadTimeoutException" in ln and ": null" not in ln and "STDERR" in ln:
        netty.append((t, idx))
    if "########## GL ERROR ##########" in ln and t is not None:
        stage = lines[idx+1] if idx+1 < len(lines) else "?"
        code = lines[idx+2] if idx+2 < len(lines) else "?"
        glerr.append((t, stage.strip(), code.strip(), idx))
    if "[CHAT]" in ln and t is not None:
        chat.append((t, ln))

unk = [p for p in pauses if p[1] == "Unknown"]
gcd = [p for p in pauses if p[1] == "GC"]

L.append("")
L.append("=== PAUSE STATS (whole session) ===")
L.append("Unknown: n=%d total=%dms max=%dms" % (len(unk), sum(p[2] for p in unk), max(p[2] for p in unk)))
L.append("GC:      n=%d total=%dms max=%dms" % (len(gcd), sum(p[2] for p in gcd), max(p[2] for p in gcd)))
L.append("GC share of all pause time: %.1f%%" % (100.0*sum(p[2] for p in gcd)/(sum(p[2] for p in pauses) or 1)))
def buckets(items, edges=(50, 100, 200, 500, 1000)):
    c = collections.Counter()
    for p in items:
        v = p[2]
        b = ">=%d" % edges[-1]
        for e in edges:
            if v < e:
                b = "<%d" % e
                break
        c[b] += 1
    return dict(c)
L.append("Unknown buckets: %s" % buckets(unk))
L.append("GC buckets:      %s" % buckets(gcd))
L.append("")
L.append("top 25 Unknown pauses:")
for p in sorted(unk, key=lambda x: -x[2])[:25]:
    L.append("   %02d:%02d:%02d  %dms" % (p[0]//3600, p[0]%3600//60, p[0]%60, p[2]))

# session window
t0 = 2*3600+58*60
L.append("")
L.append("=== PER-MINUTE TIMELINE (m | unknownPauses n/totalMs | GC ms | netty | glerr) ===")
mins = collections.defaultdict(lambda: {"pu": 0, "pum": 0, "gc": 0, "net": 0, "gl": 0})
for t, k, ms, _ in pauses:
    key = t//60
    if k == "Unknown":
        mins[key]["pu"] += 1; mins[key]["pum"] += ms
    else:
        mins[key]["gc"] += ms
for t, _ in netty:
    mins[t//60]["net"] += 1
for t, _, _, _ in glerr:
    mins[t//60]["gl"] += 1
grid = collections.Counter()
for key in sorted(mins):
    d = mins[key]
    if key*60 < 2*3600+59*60+30: continue
    L.append("%02d:%02d  unknown=%2d/%5dms  gc=%4dms  netty=%d  glerr=%d" % (key//60, key%60, d["pu"], d["pum"], d["gc"], d["net"], d["gl"]))
    grid[(d["net"] > 0, d["pu"] > 0)] += 1
L.append("")
L.append("=== 2x2 GRID netty-pattern vs pause-pattern (whole session) ===")
L.append("netty>0 & pause>0 : %d" % grid[(True, True)])
L.append("netty>0 & pause=0 : %d  <-- would disprove 'netty causes pause'" % grid[(True, False)])
L.append("netty=0 & pause>0 : %d  <-- would disprove 'needed'" % grid[(False, True)])
L.append("netty=0 & pause=0 : %d" % grid[(False, False)])

L.append("")
L.append("=== GL ERRORS ===")
for t, stage, code, idx in glerr:
    L.append("   %02d:%02d:%02d  %s | %s" % (t//3600, t%3600//60, t%60, stage, code))

L.append("")
L.append("=== CHAT LINES (last 40) ===")
for t, ln in chat[-40:]:
    L.append("   %02d:%02d:%02d %s" % (t//3600, t%3600//60, t%60, ln.split("[CHAT]")[-1][:120]))

L.append("")
L.append("=== CONTEXT lines 1645-1680 ===")
for i in range(1644, min(1682, len(lines))):
    L.append("  %4d %s" % (i+1, lines[i][:200]))

L.append("")
L.append("=== mixin conflict / redirect skips ===")
for i, ln in enumerate(lines):
    if "@Redirect conflict" in ln or "Skipping mixins" in ln or "priority" in ln and "redirect" in ln.lower():
        L.append("  %4d %s" % (i+1, ln[:250]))

open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\log_analysis.txt", "w", encoding="utf-8").write("\n".join(L))
print("ok")
