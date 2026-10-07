# 从 frame-spikes.log 里挑出最长的几次停顿，连同栈顶
import re
import os

P = os.path.expandvars(r"%USERPROFILE%\.lunarclient\profiles\1.8\framespike\frame-spikes.log")
L = open(P, encoding="utf-8", errors="replace").read().splitlines()
print("日志行数", len(L))

recs = []
i = 0
while i < len(L):
    if "STALL-DETECT" in L[i] or "SNAPSHOT " in L[i]:
        ts = L[i].split()[0] if L[i].strip() else "?"
        reason = re.search(r"reason=(\S+)", L[i])
        reason = reason.group(1) if reason else "?"
        since = re.search(r"since='([^']*)'", L[i])
        total = None
        stack = []
        j = i + 1
        mode = None
        while j < len(L) and "STALL-DETECT" not in L[j] and "SNAPSHOT " not in L[j]:
            s = L[j].strip()
            m = re.search(r"STALL-END\s+total=(\d+)ms", s)
            if m:
                total = int(m.group(1))
            if s.startswith("stack of"):
                mode = "st"
            elif mode == "st" and L[j].startswith("    "):
                if s and not s.startswith("<") and not s.startswith("..."):
                    stack.append(s)
            j += 1
        recs.append(dict(ts=ts, total=total, reason=reason,
                         since=since.group(1) if since else "?", stack=stack))
        i = j
    else:
        i += 1

done = [r for r in recs if r["total"]]
print("解析到 %d 条（其中带真实时长 %d 条）" % (len(recs), len(done)))
print()
print("=== 最长的 12 次 ===")
for r in sorted(done, key=lambda x: -x["total"])[:12]:
    print("  %7dms  %s  %-12s since=%-18s" % (r["total"], r["ts"], r["reason"], r["since"]))
    for f in r["stack"][:3]:
        print("            " + f.split("/")[-1][:120])
