import re, os, collections, datetime

LUNAR = r"C:\Users\Huang\.lunarclient\profiles\1.8\logs\latest.log"
MINE = r"C:\Users\Huang\.lunarclient\profiles\1.8\framespike\frame-spikes.log"

# ---- 1. Lunar 自己的停顿看门狗（带真实时长） ----
lp = []
t = open(LUNAR, "rb").read().decode("utf-8", "replace")
for l in t.splitlines():
    m = re.match(r"\[(\d\d):(\d\d):(\d\d)\].*Pause - (Unknown|GC) - (\d+)ms", l)
    if m:
        sec = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        lp.append((sec, m.group(4), int(m.group(5)), l[:120]))
print("=== Lunar 看门狗记录 ===")
print("总条数:", len(lp))
unk = [x for x in lp if x[1] == "Unknown"]
gc = [x for x in lp if x[1] == "GC"]
print("Unknown %d 条，累计 %dms，最大 %dms" % (len(unk), sum(x[2] for x in unk), max((x[2] for x in unk), default=0)))
print("GC      %d 条，累计 %dms，最大 %dms" % (len(gc), sum(x[2] for x in gc), max((x[2] for x in gc), default=0)))
if unk:
    b = collections.Counter()
    for s, _, v, _ in unk:
        b["<100" if v < 100 else "100-199" if v < 200 else "200-499" if v < 500 else ">=500"] += 1
    print("Unknown 分档:", dict(b))
    xs = sorted(x[2] for x in unk)
    print("最小 %d 中位 %d" % (xs[0], xs[len(xs) // 2]))
    print("全部 Unknown 值:", xs)

# ---- 2. 我的 mod 抓到的栈 ----
L = open(MINE, "rb").read().decode("utf-8", "replace").splitlines()
mine = []
i = 0
while i < len(L):
    m = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d\d\d)\s+STALL (\d+)ms\s+since='([^']*)'", L[i].strip())
    if m:
        sec = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        ms3 = int(m.group(4))
        stk = []
        j = i + 1
        mode = None
        while j < len(L) and not re.match(r"\d\d:\d\d:\d\d\.\d\d\d\s+STALL", L[j].strip()):
            s = L[j].rstrip()
            if s.strip().startswith("stack of"):
                mode = "st"
            elif s.strip().startswith("timeline:"):
                mode = "tl"
            elif mode == "st" and s.startswith("    "):
                f = s.strip()
                if f and not f.startswith("<") and not f.startswith("..."):
                    stk.append(f)
            j += 1
        mine.append(dict(sec=sec, ms3=ms3, since=m.group(6), stk=stk, line=i + 1))
        i = j
    else:
        i += 1
print("\n=== 我的 mod 抓到的停顿 ===")
print("条数:", len(mine), "（记录值全是 60-63ms —— 那是测量 bug 的产物）")

# ---- 3. 按时间戳对齐 ----
print("\n=== 对齐：Lunar 的时长 + 我的栈 ===")
matched = 0
buckets = collections.defaultdict(collections.Counter)
big = []
for sec, kind, val, raw in unk:
    cands = [r for r in mine if abs(r["sec"] - sec) <= 1]
    b = "<100" if val < 100 else "100-199" if val < 200 else "200-499" if val < 500 else ">=500"
    if cands:
        matched += 1
        top = cands[0]["stk"][0] if cands[0]["stk"] else "<无栈>"
        buckets[b][top.split("(")[0]] += 1
        if val >= 200:
            big.append((sec, val, cands[0]))
    else:
        buckets[b]["<我的 mod 没抓到（阈值 60ms 或漏采）>"] += 1
print("能配上栈的: %d / %d" % (matched, len(unk)))

print("\n=== >=200ms 停顿：时长 + 完整栈（前 16 帧，用来找谁在写文件/谁在加载）===")
for sec, val, r in sorted(big, key=lambda x: -x[1]):
    hh = "%02d:%02d:%02d" % (sec // 3600, sec % 3600 // 60, sec % 60)
    print("  %s  %3dms  since='%s'  (stack %d 帧)" % (hh, val, r["since"], len(r["stk"])))
    for f in r["stk"][:16]:
        print("        " + f.split("/")[-1][:140])
    print()

print("\n=== ≥200ms 停顿的栈顶归因 ===")
c = collections.Counter()
for sec, val, r in big:
    if r["stk"]:
        c[" | ".join(x.split("/")[-1][:60] for x in r["stk"][:3])] += 1
    else:
        c["<无栈>"] += 1
for k, v in c.most_common(12):
    print("  %2d 次  %s" % (v, k[:170]))

# ---- 4. 用户举的例子 ----
print("\n=== 定位用户提到的 12:30:37 ===")
target = 12 * 3600 + 30 * 60 + 37
for sec, kind, val, raw in lp:
    if abs(sec - target) <= 3:
        print("  Lunar: %s" % raw)
for r in mine:
    if abs(r["sec"] - target) <= 3:
        print("  我的: line %d 记录=%dms since=%s" % (r["line"], r["ms3"], r["since"]))
        for f in r["stk"][:6]:
            print("        " + f.split("/")[-1][:130])
