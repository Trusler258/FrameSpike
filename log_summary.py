import os, time, re

P = r"C:\Users\Huang\.lunarclient\profiles\1.8\framespike\frame-spikes.log"
st = os.stat(P)
print("log: %d bytes, mtime %s, now %s" % (st.st_size,
      time.strftime("%m-%d %H:%M:%S", time.localtime(st.st_mtime)),
      time.strftime("%m-%d %H:%M:%S")))
t = open(P, "rb").read().decode("utf-8", "replace")
L = t.splitlines()
print("总行数", len(L))

# 按 coremod constructed 切成会话
starts = [i for i, l in enumerate(L) if "coremod constructed" in l]
print("\n=== 会话切分：%d 个 coremod 构造（同一次启动会出现多次）===" % len(starts))

# 找每个「transformer active」块里的 patch 行，判断每轮
blocks = []
for i, l in enumerate(L):
    if l.startswith("patch "):
        blocks.append((i, l[:120]))
print("patch 行总数:", len(blocks))
seen = {}
for i, l in blocks:
    key = l.split(":")[0]
    m = re.search(r"hooks=(\d+)/(\d+)", l)
    seen.setdefault(key, []).append(m.group(0) if m else "?")
for k, v in seen.items():
    print("  %-52s %s" % (k, v))

print("\n=== 命中行 ===")
for i, l in enumerate(L):
    if "命中:" in l:
        print("  %4d %s" % (i + 1, l.strip()[:130]))

print("\n=== [cmd] 行 ===")
for l in L:
    if "[cmd]" in l:
        print("  " + l.strip()[:200])

print("\n=== [glfinish] / [ctrl] 行 ===")
for l in L:
    if "[glfinish]" in l or "[ctrl]" in l:
        print("  " + l.strip()[:200])

print("\n=== MARK 行 ===")
for l in L:
    if l.strip().startswith("MARK") or "MARK " in l and "RECORDING" not in l:
        print("  " + l.strip()[:160])

print("\n=== 记录段开始/结束 ===")
for l in L:
    if "RECORDING STARTED" in l or "RECORDING STOPPED" in l:
        print("  " + l.strip()[:160])

stalls = [(i, int(m.group(1))) for i, l in enumerate(L)
          for m in [re.search(r"  STALL (\d+)ms", l)] if m]
snaps = [i for i, l in enumerate(L) if "  SNAPSHOT " in l]
print("\n=== 停顿统计：STALL %d 次，SNAPSHOT %d 次 ===" % (len(stalls), len(snaps)))
if stalls:
    vals = sorted((v for _, v in stalls), reverse=True)
    b = {"<100": 0, "100-199": 0, "200-499": 0, ">=500": 0}
    for v in vals:
        b["<100" if v < 100 else "100-199" if v < 200 else "200-499" if v < 500 else ">=500"] += 1
    print("  最长 10:", vals[:10])
    print("  分档:", b)
    print("  累计:", sum(vals), "ms")

    print("\n=== 最长那次的前 16 行 ===")
    top_i = max(stalls, key=lambda x: x[1])[0]
    for l in L[top_i:top_i + 16]:
        print("  " + l[:170])

print("\n=== 分段汇总（RECORDING STOPPED 后几行）===")
for i, l in enumerate(L):
    if "RECORDING STOPPED" in l:
        for x in L[i:i + 6]:
            print("  " + x.strip()[:170])
        print("  ---")

print("\n=== CPU 表（最后一次）===")
idx = [i for i, l in enumerate(L) if "[CPU]" in l]
if idx:
    for l in L[idx[-1]:idx[-1] + 11]:
        print("  " + l[:150])
print("CPU 表次数:", len(idx))
