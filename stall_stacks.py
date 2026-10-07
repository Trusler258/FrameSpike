import os, re, collections

P = r"C:\Users\Huang\.lunarclient\profiles\1.8\framespike\frame-spikes.log"
L = open(P, "rb").read().decode("utf-8", "replace").splitlines()

# 解析每个 STALL 块
recs = []
i = 0
while i < len(L):
    m = re.search(r"  (STALL|SNAPSHOT) (\d+)ms  since='([^']*)'  frames=(\d+)", L[i])
    if m:
        kind, ms, since, fr = m.group(1), int(m.group(2)), m.group(3), int(m.group(4))
        tl = []
        stack = []
        j = i + 1
        mode = None
        while j < len(L) and not re.search(r"  (STALL|SNAPSHOT) \d+ms", L[j]):
            s = L[j].rstrip()
            if s.strip().startswith("timeline:"):
                mode = "tl"
            elif s.strip().startswith("stack of"):
                mode = "st"
            elif mode == "tl" and s.startswith("    ") and "t+" in s:
                mm = re.match(r"\s+(\S+)\s+t\+\s*([\d.]+)ms", s)
                if mm:
                    tl.append((mm.group(1), float(mm.group(2))))
            elif mode == "st" and s.startswith("    "):
                fr2 = s.strip()
                if fr2 and not fr2.startswith("<") and not fr2.startswith("..."):
                    stack.append(fr2)
            j += 1
        recs.append(dict(kind=kind, ms=ms, since=since, frames=fr, tl=tl, stack=stack, line=i + 1))
        i = j
    else:
        i += 1

print("解析出记录 %d 条" % len(recs))
stalls = [r for r in recs if r["kind"] == "STALL"]
print("STALL %d 条，累计 %dms" % (len(stalls), sum(r["ms"] for r in stalls)))

print("\n=== 停顿瞬间栈顶前 3 帧的频次（这是根因所在）===")
c = collections.Counter()
c1 = collections.Counter()
for r in stalls:
    if not r["stack"]:
        c["<无栈>"] += 1
        continue
    c1[r["stack"][0]] += 1
    c[" <- ".join(".".join(x.split(".")[-2:]) for x in r["stack"][:3])] += 1
for k, v in c.most_common(15):
    print("  %3d 次  %s" % (v, k[:150]))
print("\n  栈顶第 1 帧单独统计:")
for k, v in c1.most_common(12):
    print("  %3d 次  %s" % (v, k[:150]))

print("\n=== 停顿时的 since= 分布 ===")
cc = collections.Counter(r["since"] for r in stalls)
for k, v in cc.most_common():
    print("  %3d 次  %s" % (v, k))

print("\n=== 帧间隔（从时间线相邻 runGameLoop 推）===")
gaps = []
for r in stalls:
    gs = [t for (n, t) in r["tl"] if n == "runGameLoop"]
    for a, b in zip(gs, gs[1:]):
        if 2 < (b - a) < 200:
            gaps.append(round(b - a, 1))
gaps.sort()
if gaps:
    n = len(gaps)
    print("  样本 %d，中位 %.1fms，25%% %.1fms，75%% %.1fms，90%% %.1fms，max %.1fms"
          % (n, gaps[n // 2], gaps[n // 4], gaps[n * 3 // 4], gaps[int(n * 0.9)], gaps[-1]))
    print("  即约 %.1f fps（中位）" % (1000.0 / gaps[n // 2]))

print("\n=== 每次停顿里「世界渲染段」耗时（renderWorldPass 入口 -> hudForge）===")
seg = []
for r in stalls:
    d = dict()
    for n, t in r["tl"]:
        d.setdefault(n, []).append(t)
    if "renderWorldPass" in d and "hudForge" in d:
        for a in d["renderWorldPass"]:
            for b in d["hudForge"]:
                if 0 < b - a < 200:
                    seg.append(round(b - a, 1))
                    break
seg.sort()
if seg:
    n = len(seg)
    print("  样本 %d，中位 %.2fms，最大 %.2fms" % (n, seg[n // 2], seg[-1]))

print("\n=== 三个最长停顿的完整块 ===")
for r in sorted(stalls, key=lambda x: -x["ms"])[:3]:
    print("\n--- line %d: STALL %dms since=%s frames=%d ---" % (r["line"], r["ms"], r["since"], r["frames"]))
    for n, t in r["tl"]:
        print("      %-22s t+%9.3f" % (n, t))
    for s in r["stack"][:22]:
        print("      " + s[:150])
