# 扫出「Java 字符串里夹半角双引号包中文」这种会截断字符串的写法
import re, glob, sys

pat = re.compile('[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]"[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]')
tot = 0
for p in sorted(glob.glob("src/framespike/*.java")) + sorted(glob.glob("test/*.java")):
    for i, l in enumerate(open(p, encoding="utf-8").read().splitlines(), 1):
        if pat.search(l):
            tot += 1
            print("  %s:%d  %s" % (p, i, l.strip()[:130]))
print("危险行数:", tot)
sys.exit(1 if tot else 0)
