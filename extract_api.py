import os, zipfile, sys

OUT = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\lib"
GEN = r"C:\Users\Huang\.lunarclient\offline\multiver\genesis-0.1.0-SNAPSHOT-all.jar"
FORGE = r"C:\Users\Huang\.lunarclient\offline\multiver\Forge_v1_8.jar"

def dump(src, prefixes, dst):
    z = zipfile.ZipFile(src)
    n = 0
    for e in z.namelist():
        if not e.endswith(".class"):
            continue
        if not any(e.startswith(p) for p in prefixes):
            continue
        t = os.path.join(dst, e.replace("/", os.sep))
        os.makedirs(os.path.dirname(t), exist_ok=True)
        open(t, "wb").write(z.read(e))
        n += 1
    return n

print("asm:", dump(GEN, ["org/objectweb/asm/"], os.path.join(OUT, "stubapi")))
print("launchwrapper:", dump(GEN, ["net/minecraft/launchwrapper/IClassTransformer"], os.path.join(OUT, "stubapi")))
print("fml:", dump(FORGE, ["net/minecraftforge/fml/relauncher/IFMLLoadingPlugin"], os.path.join(OUT, "stubapi")))
