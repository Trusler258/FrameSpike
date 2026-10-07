import os, re

R = []
root = r"C:\Users\Huang\.lunarclient\profiles\1.8"
for sub in ("mods", "mods\\forge-1.8.9", "mods\\1.8.9", ""):
    p = os.path.join(root, sub)
    if os.path.isdir(p):
        R.append("### DIR %s" % p)
        for f in sorted(os.listdir(p)):
            fp = os.path.join(p, f)
            if os.path.isfile(fp):
                R.append("   %10d  %s" % (os.path.getsize(fp), f))
            else:
                R.append("   <dir>       %s" % f)

for fn in ("optionsof.txt", "options.txt", "optionsshaders.txt"):
    fp = os.path.join(root, fn)
    if os.path.exists(fp):
        R.append("")
        R.append("### %s" % fp)
        try:
            txt = open(fp, "rb").read().decode("utf-8", "replace")
        except Exception as e:
            txt = "ERR %s" % e
        keep = []
        for ln in txt.splitlines():
            if re.search(r"(shader|Shader|ofSmooth|ofFast|ofAa|ofFps|ofLoad|limitFramerate|vsync|renderDistance|fbo|ofClouds|ofTrees|ofDropped|ofChunk|ofLazy|ofDynamic|ofWeather|ofSun|ofMoon|ofVignette|ofClear|particles|graphics|guiScale|ofAo|ofBetter|ofWet|ofRain|ofSnow|advanced|ofTranslucent|ofWide|gamma|ofNaturalTexture|ofCustom)", ln):
                keep.append(ln)
        R.append("\n".join(keep) if keep else "(no interesting keys)")

os.makedirs(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50", exist_ok=True)
open(r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\mc_settings.txt", "w", encoding="utf-8").write("\n".join(R))
print("ok")
