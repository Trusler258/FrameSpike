import re, collections, os, time

P = r"C:\Users\Huang\.lunarclient\profiles\1.8\framespike\frame-spikes.log"
print("log: %d bytes, mtime %s" % (os.path.getsize(P),
      time.strftime("%H:%M:%S", time.localtime(os.path.getmtime(P)))))
L = open(P, "rb").read().decode("utf-8", "replace").splitlines()
print("总行数", len(L))

print("\n=== RECORDING 行（含启动时的阈值）===")
segs = []
for i, l in enumerate(L):
    if "RECORDING STARTED" in l or "RECORDING STOPPED" in l:
        print("  %4d %s" % (i + 1, l.strip()[:170]))
        if "STARTED" in l:
            m = re.search(r"threshold=(\d+)ms", l)
            segs.append((i, int(m.group(1)) if m else -1))
print("  开始记录次数:", len(segs), "阈值:", [s[1] for s in segs])

print("\n=== 全部 [cmd] 行（含 tabComplete / 注册）===")
for l in L:
    if "[cmd]" in l:
        print("  " + l.strip()[:160])

print("\n=== 全部 STALL 的 ms 值分布（整份日志）===")
vals = []
for l in L:
    m = re.search(r"  STALL (\d+)ms  since='([^']*)'", l)
    if m:
        vals.append((int(m.group(1)), m.group(2)))
print("  条数:", len(vals))
if vals:
    xs = sorted(v for v, _ in vals)
    print("  最小 %d  最大 %d  中位 %d" % (xs[0], xs[-1], xs[len(xs) // 2]))
    print("  直方图:")
    b = collections.Counter()
    for v, _ in vals:
        b[(v // 10) * 10] += 1
    for k in sorted(b):
        print("    %3d-%3dms : %s %d" % (k, k + 9, "#" * min(b[k], 60), b[k]))

print("\n=== 最后一个「开始记录」之后的 STALL ===")
if segs:
    si = segs[-1][0]
    after = [(i + 1, int(m.group(1)), m.group(2))
             for i, l in enumerate(L) if i > si
             for m in [re.search(r"  STALL (\d+)ms  since='([^']*)'", l)] if m]
    print("  条数 %d" % len(after))
    if after:
        xs = sorted(v for _, v, _ in after)
        print("  最小 %d 最大 %d" % (xs[0], xs[-1]))
        print("  各次:", [(v, s) for _, v, s in after])

print("\n=== 该段内的 CPU 表 ===")
for i, l in enumerate(L):
    if "[CPU]" in l and (not segs or i > segs[-1][0]):
        print("  " + l.strip()[:140])
