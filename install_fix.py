import os, shutil, hashlib

MODS = os.path.expandvars(r"%USERPROFILE%\.lunarclient\profiles\1.8\mods\forge-1.8.9")
NEW = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\dist\FrameSpike-Forge-1.8.9-1.12.2-0.7.1.jar"
NEWNAME = os.path.basename(NEW)


def md5(p):
    return hashlib.md5(open(p, "rb").read()).hexdigest()[:12]


def uniq(base):
    """避免和已有备份重名；一律用 rename（沙箱不允许删工作区外的文件）"""
    if not os.path.exists(base):
        return base
    i = 2
    while os.path.exists("%s-%d" % (base, i)):
        i += 1
    return "%s-%d" % (base, i)


print("新 jar:", NEWNAME, os.path.getsize(NEW), md5(NEW))

# 0) 先确认旧 jar 没被占用（游戏开着时 JVM 会锁住它）。
#    此时**绝不能装**：两个 FrameSpike jar 同时存在会被 FML 同时加载 -> 双份字节码注入。
locked = []
if os.path.isdir(MODS):
    for f in sorted(os.listdir(MODS)):
        if f.startswith("FrameSpike-") and f.endswith(".jar"):
            p = os.path.join(MODS, f)
            try:
                test = p + ".locktest"
                os.rename(p, test)
                os.rename(test, p)
            except Exception as e:
                locked.append((f, e))
if locked:
    print()
    print("*** 装不了：旧 jar 正被占用（游戏还开着） ***")
    for f, e in locked:
        print("    %s  ->  %s" % (f, e))
    print()
    print("请先【完全退出 Lunar Client】，再重跑本脚本：")
    print("    python install_fix.py")
    print("（这一步故意不硬来：新旧两个 FrameSpike jar 同时存在会被加载两次，反而更糟）")
    raise SystemExit(1)

# 1) 旧版一律 rename 成 .bak-*（.bak 不会被 FML 加载）
if os.path.isdir(MODS):
    for f in sorted(os.listdir(MODS)):
        if (not f.startswith("FrameSpike-")) or (not f.endswith(".jar")):
            continue
        p = os.path.join(MODS, f)
        if f == NEWNAME:
            bak = uniq("%s.bak-%d" % (p, os.path.getsize(p)))
            os.rename(p, bak)
            print("同名旧版已挪走 ->", os.path.basename(bak), md5(bak))
            continue
        bak = uniq("%s.bak-%d" % (p, os.path.getsize(p)))
        os.rename(p, bak)
        print("旧版已挪走 ->", os.path.basename(bak), os.path.getsize(bak), md5(bak))

# 2) 安装
dst = os.path.join(MODS, NEWNAME)
try:
    shutil.copy2(NEW, dst)
    print("已安装 ->", NEWNAME, os.path.getsize(dst), md5(dst))
except PermissionError as e:
    print("*** 复制失败（文件被占用，游戏多半还开着）：", e)
    print("*** 请先完全退出 Lunar Client，再重跑本脚本")
except Exception as e:
    print("*** 复制失败:", type(e).__name__, e)

print()
print("mods 目录内容：")
live = []
for f in sorted(os.listdir(MODS)):
    p = os.path.join(MODS, f)
    if f.startswith("FrameSpike-") and f.endswith(".jar"):
        live.append(f)
    print("   %8d  %s" % (os.path.getsize(p), f))
print()
print("当前会被 FML 加载的 FrameSpike jar：", live if live else "(无)")
if len(live) > 1:
    print("*** 警告：有多个 FrameSpike jar 会同时加载，必须只留一个！")
elif len(live) == 1:
    print("OK：只有一个，且是", live[0])
