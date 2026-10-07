import re, collections

P = r"C:\Users\Huang\.lunarclient\profiles\1.8\framespike\frame-spikes.log"
L = open(P, "rb").read().decode("utf-8", "replace").splitlines()

recs = []
i = 0
while i < len(L):
    m = re.search(r"  (STALL|SNAPSHOT) (\d+)ms  since='([^']*)'", L[i])
    if m:
        ms, since = int(m.group(2)), m.group(3)
        stack = []
        j = i + 1
        mode = None
        while j < len(L) and not re.search(r"  (STALL|SNAPSHOT) \d+ms", L[j]):
            s = L[j].rstrip()
            if s.strip().startswith("stack of"):
                mode = "st"
            elif s.strip().startswith("timeline:"):
                mode = "tl"
            elif mode == "st" and s.startswith("    "):
                f = s.strip()
                if f and not f.startswith("<") and not f.startswith("..."):
                    stack.append(f)
            j += 1
        recs.append((ms, since, stack))
        i = j
    else:
        i += 1

def klass(stk):
    top5 = " ".join(stk[:5])
    if re.search(r"defineClass|ClassLoader|findClass|ClassReader|ClassWriter|SymbolTable|MethodWriter|"
                 r"ByteVector|MethodNode|org\.objectweb\.asm|ZipFile|JarFile|ZipCoder|Inflater|"
                 r"GetFileAttributes|RandomAccessFile|MethodHandles|\$\$Lambda|cadixdev|"
                 r"URLClassPath|inject\.util|Class\.get|CharsetUtil\.<clinit>|netty\.util", top5):
        return "类加载 / remap / 字节码"
    if "WindowsDisplay" in top5:
        return "Windows 窗口合成 / 消息泵"
    if re.search(r"GL11\.|GL15\.|GL30\.|ngl|Display", top5):
        return "GL 驱动调用"
    if re.search(r"SoundSystem|SoundManager|paulscode|CommandQueue", top5):
        return "音效系统"
    if "FontRenderer" in top5:
        return "字体渲染"
    if re.search(r"FileOutputStream|FileChannel|writeBytes", top5):
        return "文件写入"
    return "其他（零散一次性）"

agg = collections.defaultdict(lambda: [0, 0, 0])   # 次数, 累计ms, 最长
for ms, since, stk in recs:
    k = klass(stk)
    a = agg[k]
    a[0] += 1
    a[1] += ms
    a[2] = max(a[2], ms)

total_ms = sum(r[0] for r in recs)
print("=== %d 次停顿，累计 %d ms，按归因（栈顶前 5 帧）===" % (len(recs), total_ms))
print("%-24s %5s %9s %9s %7s" % ("归因", "次数", "累计ms", "最长ms", "占累计"))
for k, v in sorted(agg.items(), key=lambda x: -x[1][1]):
    print("%-24s %5d %9d %9d %6.1f%%" % (k, v[0], v[1], v[2], 100.0 * v[1] / total_ms))
print()
print("总占比检查:", sum(v[1] for v in agg.values()), "ms")

print()
print("=== since= × 归因 交叉表 ===")
cross = collections.defaultdict(lambda: collections.defaultdict(int))
for ms, since, stk in recs:
    cross[klass(stk)][since] += 1
for k, d in sorted(cross.items(), key=lambda x: -sum(x[1].values())):
    print("  %-24s %s" % (k, dict(d)))

print()
print("=== 「类加载」类停顿的栈顶帧明细（这是最大嫌疑） ===")
detail = collections.Counter()
for ms, since, stk in recs:
    if klass(stk) == "类加载 / 字节码处理":
        for f in stk[:3]:
            detail[" <- ".join(x.split("/")[-1] for x in stk[:3])] += 1
            break
for k, v in detail.most_common():
    print("  %2d 次  %s" % (v, k[:160]))
print()
print("=== 这些类加载停顿里，Lunar 自己的 classloader 出现次数 ===")
n_lunar = 0
for ms, since, stk in recs:
    if any("findClass" in f or "ROOCCIHRCCHORHCCOIIRIHHHIIHHHO" in f for f in stk[:6]):
        n_lunar += 1
print("  %d / %d" % (n_lunar, len(recs)))
for ms, since, stk in recs:
    if any("findClass" in f for f in stk[:6]):
        print("   例: %dms  " % ms + " | ".join(f.split("/")[-1][:60] for f in stk[:5]))
        break
