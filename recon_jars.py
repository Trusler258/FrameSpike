import os, zipfile, re, json, shutil, sys

ROOTS = [r"C:\Users\Huang\.lunarclient\offline",
         r"C:\Users\Huang\.lunarclient\profiles\1.8"]
OUT = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\recon"
os.makedirs(OUT, exist_ok=True)

jars = []
for base in ROOTS:
    for root, d, fs in os.walk(base):
        for f in fs:
            if f.lower().endswith(".jar"):
                jars.append(os.path.join(root, f))
jars = sorted(set(jars))

PROBES = {
    "asm_core": "org/objectweb/asm/ClassReader.class",
    "asm_tree": "org/objectweb/asm/tree/InsnList.class",
    "asm_module_visitor_ASM6+": "org/objectweb/asm/ModuleVisitor.class",
    "asm_RecordComponent_ASM8+": "org/objectweb/asm/RecordComponentVisitor.class",
    "asm_SymbolTable_ASM7+": "org/objectweb/asm/SymbolTable.class",
    "asm_ConstantDynamic_ASM7+": "org/objectweb/asm/ConstantDynamic.class",
    "fml_IFMLLoadingPlugin": "net/minecraftforge/fml/relauncher/IFMLLoadingPlugin.class",
    "lw_IClassTransformer": "net/minecraft/launchwrapper/IClassTransformer.class",
    "MC_Minecraft_MCP": "net/minecraft/client/Minecraft.class",
    "MC_EntityRenderer_MCP": "net/minecraft/client/renderer/EntityRenderer.class",
    "MC_GuiIngameForge_MCP": "net/minecraftforge/client/GuiIngameForge.class",
    "mixin_core": "org/spongepowered/asm/mixin/Mixin.class",
}

R = []
R.append("### JARS FOUND: %d" % len(jars))
for j in jars:
    R.append("  %.1f MB  %s" % (os.path.getsize(j)/1048576.0, j.replace(r"C:\Users\Huang\.lunarclient\\", "")))

# ---- ASM version fingerprint by reading ClassReader.class major version + probe set
R.append("")
R.append("### ASM / FML CAPABILITY MATRIX")
for j in jars:
    try:
        z = zipfile.ZipFile(j)
    except Exception as e:
        R.append("  BAD %s : %s" % (j, e)); continue
    names = set(z.namelist())
    hits = [k for k, v in PROBES.items() if v in names]
    if not hits:
        continue
    R.append("  --- %s" % os.path.basename(j))
    hdr = ""
    if PROBES["asm_core"] in names:
        b = z.read(PROBES["asm_core"])
        major = int.from_bytes(b[6:8], "big")
        hdr = "  [ClassReader compiled for class-file major %d]" % major
        # try to sniff ASM version string constant
        m = re.findall(rb"(ASM[0-9]+)", b)
        hdr += " consts=%s" % sorted(set(x.decode() for x in m))
    R.append("      %s%s" % (", ".join(hits), hdr))

# ---- find glFinish call sites
R.append("")
R.append("### CLASSES REFERENCING org/lwjgl/opengl/GL11 (+glFinish)")
GL11 = b"org/lwjgl/opengl/GL11"
FIN = b"glFinish"
found = []
for j in jars:
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    for n in z.namelist():
        if not n.endswith(".class"):
            continue
        try:
            b = z.read(n)
        except Exception:
            continue
        if FIN in b and GL11 in b:
            cnt = b.count(FIN)
            found.append((j, n, len(b), cnt))
for j, n, sz, c in found:
    R.append("  %-14s cnt=%-3d %8d  %s" % (os.path.basename(j), c, sz, n))

# extract candidates for javap
cand_dir = os.path.join(OUT, "cand")
os.makedirs(cand_dir, exist_ok=True)
extracted = []
for idx, (j, n, sz, c) in enumerate(found):
    z = zipfile.ZipFile(j)
    data = z.read(n)
    dst = os.path.join(cand_dir, "c%02d_%s" % (idx, os.path.basename(n)))
    open(dst, "wb").write(data)
    extracted.append((dst, j, n))
R.append("")
R.append("### EXTRACTED FOR JAVAP")
for d, j, n in extracted:
    R.append("  %s   <- %s!%s" % (d, os.path.basename(j), n))

# ---- obfuscation check: look for obf class names of Minecraft in the main game jar
R.append("")
R.append("### OBF/MCP NAMING CHECK (which jar holds the game classes)")
for j in jars:
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    names = z.namelist()
    mcp = sum(1 for n in names if n.startswith("net/minecraft/") and n.endswith(".class"))
    obf = sum(1 for n in names if re.fullmatch(r"[a-z]{1,4}/[a-z]{1,3}\.class", n))
    root_obf = sum(1 for n in names if re.fullmatch(r"[a-z]{1,4}\.class", n))
    if mcp or root_obf > 50:
        R.append("  %-42s mcp_classes=%-6d obf_pkg=%-5d obf_root=%-5d" % (os.path.basename(j), mcp, obf, root_obf))

open(os.path.join(OUT, "recon.txt"), "w", encoding="utf-8").write("\n".join(R))
print("ok")
