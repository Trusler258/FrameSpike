import os, zipfile, io, subprocess, re

R = []
def add(s=""):
    R.append(str(s))

MAPJAR = r"C:\Users\Huang\.lunarclient\offline\multiver\lunar-platform-mappings-v1_8.jar"
VJAR = os.path.expandvars(r"%APPDATA%\.minecraft\versions\1.8.9\1.8.9.jar")
JAVAP = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin\javap.exe"
TMP = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\recon\kin"
os.makedirs(TMP, exist_ok=True)

def tokens(buf):
    out = []
    i = 0
    n = len(buf)
    while i < n:
        L = buf[i]
        if 1 <= L < 0x80 and i + 1 + L <= n:
            s = buf[i+1:i+1+L]
            if all(0x20 <= c < 0x7f for c in s):
                out.append((i, s.decode("ascii")))
                i += 1 + L
                continue
        i += 1
    return out

def looks_class(t):
    return "/" in t and not t.startswith(("(", "[", "L")) and not t.startswith(("func_", "field_")) and "." not in t

def looks_desc(t):
    if t.startswith("("):
        return True
    if t.startswith("["):
        return True
    if len(t) == 1 and t in "ZBCSIJFD":
        return True
    if t.startswith("L") and t.endswith(";"):
        return True
    return False

z = zipfile.ZipFile(MAPJAR)
add("### 映射 jar 内条目")
for n in z.namelist():
    add("   %-45s %8d" % (n, len(z.read(n)) if not n.endswith("/") else 0))

KIN = "forge/mcp_searge_1.8.9.kin"
buf = z.read(KIN)
toks = tokens(buf)
add()
add("### %s  token 数 = %d" % (KIN, len(toks)))

# 按类切分
records = {}
cur = None
for idx, (off, t) in enumerate(toks):
    if looks_class(t) and t.startswith("net/minecraft"):
        cur = t
        records.setdefault(cur, [])
        continue
    if cur is not None and t.startswith(("func_", "field_")):
        # 前两个 token 应该是 notch名 与 描述符
        if idx >= 2:
            notch = toks[idx-2][1]
            desc = toks[idx-1][1]
            if looks_desc(desc) and not looks_class(notch) and not notch.startswith(("func_", "field_")):
                records[cur].append((notch, desc, t))

add("  解析出类记录 %d 个" % len(records))

TARGETS = {
    "net/minecraft/client/Minecraft": ["func_71411_J", "func_71407_l"],
    "net/minecraft/client/renderer/EntityRenderer": ["func_181560_a", "func_78471_a", "func_175068_a"],
    "net/minecraft/client/gui/GuiIngame": ["func_175180_a"],
    "net/minecraftforge/client/GuiIngameForge": ["func_175180_a"],
    "net/minecraft/client/renderer/RenderGlobal": ["func_174970_a"],
    "net/minecraft/command/ICommand": ["func_71517_b", "func_71515_b", "func_71514_a"],
    "net/minecraft/command/CommandHandler": ["func_71560_a", "func_71555_a"],
    "net/minecraft/command/ICommandSender": ["func_145747_a"],
}
add()
add("### 目标方法的 notch 名（关键产出）")
OUT = {}
for cls, srgs in TARGETS.items():
    recs = records.get(cls)
    if recs is None:
        add("  %s : 映射表里没有这个类记录" % cls)
        continue
    add("  --- %s （映射表共 %d 个成员）" % (cls, len(recs)))
    for notch, desc, srg in recs:
        if srg in srgs:
            OUT[(cls, srg)] = (notch, desc)
            add("      %-14s %-45s  notch=%s" % (srg, desc, notch))
    if not any(s in [x[2] for x in recs] for s in srgs):
        add("      (目标 SRG 没找到，列出前 20 个成员供参考)")
        for notch, desc, srg in recs[:20]:
            add("        %-14s %-40s  notch=%s" % (srg, desc, notch))

# 验证：从原版 jar 里把对应的 notch 类抽出来 javap，确认方法名确实存在
add()
add("### 离线验证：原版 1.8.9.jar 里这些 notch 方法是否真的存在")
vz = zipfile.ZipFile(VJAR)
names = vz.namelist()

# 先找 Minecraft 的 notch 类：含 INVOKESTATIC org/lwjgl/opengl/Display 的那个
cand = []
for n in names:
    if not n.endswith(".class") or "/" in n:
        continue
    b = vz.read(n)
    if b"org/lwjgl/opengl/Display" in b:
        cand.append((n, b.count(b"invoke"), len(b)))
add("  引用 org/lwjgl/opengl/Display 的顶层类: %s" % [(c[0], c[2]) for c in cand])

def javap_methods(classbytes, cname):
    p = os.path.join(TMP, cname)
    open(p, "wb").write(classbytes)
    try:
        r = subprocess.run([JAVAP, "-p", p], capture_output=True, text=True, encoding="utf-8", errors="replace")
        return r.stdout
    except Exception as e:
        return "javap 失败: %s" % e

for n, _c, _s in cand[:3]:
    out = javap_methods(vz.read(n), n)
    methods = re.findall(r"^\s{2}(?:public|private|protected|static|final|\s)*?[\w$.\[\]]+\s+(\w+)\((.*?)\)", out, re.M)
    sigs = set((m[0], "(" + m[1] + ")") for m in methods)
    add("  --- %s : 解析出 %d 个方法" % (n, len(sigs)))
    for (cls, srg), (notch, desc) in OUT.items():
        if cls != "net/minecraft/client/Minecraft":
            continue
        ok = (notch, desc) in sigs
        add("      %s (%s) notch=%s%s  %s" % (srg, cls.split("/")[-1], notch, desc,
                                              "存在" if ok else "*** 不存在 ***"))
    add("      该类的 ()V 方法: %s" % sorted([x[0] for x in sigs if x[1] == "()V"])[:30])
    break

open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\kin_out.txt", "w", encoding="utf-8").write("\n".join(R))
print("ok")
