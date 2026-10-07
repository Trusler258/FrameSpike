#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
join_stalls.py - 把三份数据对齐，回答"这次停顿是不是外部因素造成的"

输入（默认路径都能自动找到）：
  1. frame-spikes.log   mod 抓的停顿（含栈、含 since 标签；新格式还有真实时长 total=）
  2. latest.log         Lunar 看门狗的真实时长（Pause - Unknown - NNNms）
  3. sysprobe.jsonl     sysprobe.ps1 采的整机状态（CPU/DPC/ISR/磁盘队列与延迟/逐进程 CPU 与 IO）

用法：
    python join_stalls.py
    python join_stalls.py --min-ms 200
    python join_stalls.py --mine <路径> --lunar <路径> --sys <路径>

判定口径（每条 ≥min-ms 的停顿都会被问一遍）：
    javaw 之外有进程 CPU > 20%      -> 有别的进程在抢
    DPC 或 ISR > 2%                 -> 驱动级中断异常
    磁盘队列 > 0                     -> 磁盘排队
    磁盘写延迟 > 50ms                -> 磁盘/过滤驱动（杀软）干预
    四项都没有                       -> 找不出外部因素，问题在进程内部
"""
import os
import re
import sys
import json
import argparse
from datetime import datetime

HOME = os.path.expanduser("~")
DEF_STALL = os.path.join(HOME, ".lunarclient", "profiles", "1.8", "framespike", "frame-spikes.log")
DEF_LUNAR = os.path.join(HOME, ".lunarclient", "profiles", "1.8", "logs", "latest.log")
DEF_SYS = os.path.join(HOME, ".lunarclient", "profiles", "1.8", "framespike", "sysprobe.jsonl")

TS = re.compile(r"(\d\d):(\d\d):(\d\d)(?:\.(\d\d\d))?")


def sec(h, m, s):
    return int(h) * 3600 + int(m) * 60 + int(s)


def parse_mine(path):
    """返回 [{t, ts, detect, total, since, stack[]}]，兼容新旧两种格式"""
    if not os.path.isfile(path):
        return []
    lines = open(path, "rb").read().decode("utf-8", "replace").splitlines()
    eps = []
    cur = None
    i = 0
    while i < len(lines):
        ln = lines[i]
        m = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d\d\d)\s+(STALL-DETECT|STALL|SNAPSHOT)\s+(\d+)ms\s+since='([^']*)'", ln.strip())
        if m:
            cur = dict(t="%s:%s:%s" % (m.group(1), m.group(2), m.group(3)),
                       ts=sec(m.group(1), m.group(2), m.group(3)),
                       detect=int(m.group(6)), total=None, since=m.group(7),
                       kind=m.group(5), stack=[])
            eps.append(cur)
            i += 1
            continue
        if cur is not None:
            mm = re.search(r"STALL-END\s+total=(\d+)ms", ln)
            if mm:
                cur["total"] = int(mm.group(1))
                cur = None
                i += 1
                continue
            s = ln.rstrip()
            if s.strip().startswith("stack of"):
                i += 1
                while i < len(lines) and lines[i].startswith("    "):
                    f = lines[i].strip()
                    if f and not f.startswith("<") and not f.startswith("..."):
                        cur["stack"].append(f)
                    i += 1
                continue
        i += 1
    return eps


def parse_lunar(path):
    """Lunar 看门狗的真实时长"""
    out = []
    if not os.path.isfile(path):
        return out
    for ln in open(path, "rb").read().decode("utf-8", "replace").splitlines():
        m = re.match(r"\[(\d\d):(\d\d):(\d\d)\].*Pause - (Unknown|GC) - (\d+)ms", ln)
        if m:
            out.append(dict(ts=sec(m.group(1), m.group(2), m.group(3)),
                            t="%s:%s:%s" % (m.group(1), m.group(2), m.group(3)),
                            kind=m.group(4), ms=int(m.group(5))))
    return out


def parse_sys(path):
    out = []
    if not os.path.isfile(path):
        return out
    for ln in open(path, "rb").read().decode("utf-8", "replace").splitlines():
        ln = ln.lstrip("\ufeff").strip()
        if not ln.startswith("{"):
            continue
        try:
            d = json.loads(ln)
        except Exception:
            continue
        m = TS.match(d.get("t", ""))
        if not m:
            continue
        d["ts"] = sec(m.group(1), m.group(2), m.group(3))
        if m.group(4):
            d["ts"] += int(m.group(4)) / 1000.0
        out.append(d)
    out.sort(key=lambda x: x["ts"])
    return out


def fmt(v, nd=1):
    return ("%." + str(nd) + "f") % v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mine", default=DEF_STALL)
    ap.add_argument("--lunar", default=DEF_LUNAR)
    ap.add_argument("--sys", default=DEF_SYS)
    ap.add_argument("--min-ms", type=int, default=200)
    ap.add_argument("--window", type=float, default=2.0, help="停顿前后各取多少秒")
    args = ap.parse_args()

    eps = parse_mine(args.mine)
    lun = parse_lunar(args.lunar)
    sy = parse_sys(args.sys)

    print("=== 输入 ===")
    print("  mod 停顿记录 : %d 条  (%s)" % (len(eps), args.mine))
    n_total = sum(1 for e in eps if e["total"] is not None)
    print("               其中带真实时长(STALL-END)的 %d 条" % n_total)
    print("  Lunar 时长   : %d 条 Unknown / %d 条 GC" %
          (sum(1 for x in lun if x["kind"] == "Unknown"), sum(1 for x in lun if x["kind"] == "GC")))
    print("  整机采样     : %d 条" % len(sy))
    if not sy:
        print("\n[提示] 整机采样为空 —— 先另开一个管理员 PowerShell 跑：")
        print("       powershell -ExecutionPolicy Bypass -File probe\\sysprobe.ps1")
        print("       然后再打一局；本脚本依然会给出 mod 侧结论，只是没有整机对照。")

    # 用 Lunar 的时长给没有 total 的老记录补时长
    for e in eps:
        if e["total"] is not None:
            continue
        best = None
        for x in lun:
            if x["kind"] != "Unknown":
                continue
            d = abs(x["ts"] - e["ts"])
            if d <= 1 and (best is None or d < best[0]):
                best = (d, x["ms"], x["ts"])
        if best:
            e["total"] = best[1]
            e["dur_src"] = "Lunar"
            e["pause_key"] = ("pause", best[2])
        else:
            e["total"] = e["detect"]
            e["dur_src"] = "detect(不准)"
            e["pause_key"] = ("self", e["ts"])

    # 整机基线
    if sy:
        sysrec = [s for s in sy if s.get("kind") == "sys"]
        if sysrec:
            print("\n=== 整机基线（所有 sys 采样）===")
            for k, nd in (("cpu", 1), ("dpc", 2), ("isr", 2), ("diskQ", 2), ("wMs", 2), ("memMB", 0)):
                vs = [s.get(k, 0) for s in sysrec if k in s]
                if vs:
                    print("  %-7s 中位 %8s   最大 %8s" % (k, fmt(sorted(vs)[len(vs) // 2], nd), fmt(max(vs), nd)))

    # 归组：一次长冻结里我的 mod 可能打多条（每个阶段间隔都超过阈值），
    # 靠"匹配到同一条 Lunar 停顿"把它们合成一组，避免重复计数。
    groups = {}
    for e in eps:
        if e["kind"] == "SNAPSHOT":
            continue
        groups.setdefault(e["pause_key"], []).append(e)
    big = []
    for k, members in groups.items():
        dur = max((m["total"] or 0) for m in members)
        if dur < args.min_ms:
            continue
        members.sort(key=lambda x: x["ts"])
        big.append(dict(key=k, total=dur, members=members,
                        t=members[0]["t"], ts=members[0]["ts"],
                        since=",".join(m["since"] for m in members),
                        src=members[0].get("dur_src", "mod"),
                        detect=members[0]["detect"]))
    big.sort(key=lambda x: -x["total"])

    print("\n=== >= %dms 的停顿：%d 次冻结（含 %d 条 mod 记录）==="
          % (args.min_ms, len(big), sum(len(b["members"]) for b in big)))
    flags = {"其他进程抢CPU": 0, "DPC/ISR高": 0, "磁盘排队": 0, "磁盘写延迟高": 0,
             "无外部异常": 0, "无整机数据": 0}
    for b in big:
        lo = b["ts"] - args.window
        hi = b["ts"] + b["total"] / 1000.0 + args.window
        win = [s for s in sy if lo <= s["ts"] <= hi]
        swin = [s for s in win if s.get("kind") == "sys"]
        pwin = [s for s in win if s.get("kind") == "proc"]
        print("\n--- %s  %d ms  (时长来源 %s, %d 条记录, since=%s) ---"
              % (b["t"], b["total"], b["src"], len(b["members"]), b["since"]))
        for m in b["members"]:
            tag = m["stack"][0].split("/")[-1][:110] if m["stack"] else "<无栈>"
            print("      [%s] %s" % (m["since"], tag))
        if not swin and not pwin:
            print("      整机: 窗口内没有采样（sysprobe 当时没在跑）")
            flags["无整机数据"] += 1
            continue
        if swin:
            print("      整机: cpu max %s%%   dpc max %s%%   isr max %s%%   diskQ max %s   wMs max %s   memMB min %s"
                  % (fmt(max(s.get("cpu", 0) for s in swin), 1),
                     fmt(max(s.get("dpc", 0) for s in swin), 2),
                     fmt(max(s.get("isr", 0) for s in swin), 2),
                     fmt(max(s.get("diskQ", 0) for s in swin), 2),
                     fmt(max(s.get("wMs", 0) for s in swin), 2),
                     fmt(min(s.get("memMB", 0) for s in swin), 0)))
        if pwin:
            near = min(pwin, key=lambda s: abs(s["ts"] - b["ts"]))
            others = [(p["n"], p["v"]) for p in near.get("topCpu", [])
                      if p["n"].lower() not in ("javaw", "idle")]
            io = [(p["n"], p["kb"]) for p in near.get("topIO", [])][:4]
            print("      进程: 除 javaw 外 CPU 最高 %s" % (others[:3] if others else "无"))
            print("      磁盘IO: %s" % (io if io else "无"))

        hit = []
        for s in pwin:
            for p in s.get("topCpu", []):
                if p["n"].lower() not in ("javaw", "idle") and p["v"] > 20:
                    hit.append("其他进程抢CPU")
                    break
        for s in swin:
            if s.get("dpc", 0) > 2 or s.get("isr", 0) > 2:
                hit.append("DPC/ISR高")
                break
        for s in swin:
            if s.get("diskQ", 0) > 0:
                hit.append("磁盘排队")
                break
        for s in swin:
            if s.get("wMs", 0) > 50:
                hit.append("磁盘写延迟高")
                break
        if not hit:
            flags["无外部异常"] += 1
            print("      判定: 整机侧看不出异常 -> 问题更可能在进程内部")
        else:
            for h in set(hit):
                flags[h] += 1
            print("      判定: 整机侧可见 -> %s" % "、".join(sorted(set(hit))))

    print("\n=== 汇总（%d 次 >= %dms 的冻结）===" % (len(big), args.min_ms))
    for k, v in flags.items():
        if v:
            print("  %-14s %d" % (k, v))
    if len(big) == 0:
        print("  （本段没有这么长的停顿）")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
