#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键构建 FrameSpike 的三个平台产物。

  1.8.9 / 1.12.2  : Forge coremod（build.py，纯 javac + jar，无 Gradle）
  1.16.5+         : Fabric   （fabric/  Gradle + Loom）
  1.20.1+         : NeoForge （neoforge/ Gradle + NeoGradle）

用法：
    python build_all.py                # 三个都编
    python build_all.py forge          # 只编 1.8.9 coremod
    python build_all.py fabric neoforge
    python build_all.py --skip-tests   # 跳过 1.8.9 的四套离线自检（赶时间时用）

关于代理（这台机器踩到的真坑）：
    Windows 上设了 HTTP_PROXY/HTTPS_PROXY 时，Python(urllib) 会自动走代理，
    但 **Gradle 默认不读这两个环境变量** —— 表现是某些仓库（如 maven.neoforged.net）
    直连被 reset，报 'Got socket exception ... Connection refused/reset'，
    而且错误信息看起来像是仓库地址或插件坐标写错了，非常容易带偏。
    所以这里自动把环境里的代理转成 -Dhttps.proxyHost/-Dhttps.proxyPort 传给 Gradle。
"""
import os
import re
import shutil
import subprocess
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
GRADLE = r"C:\Users\Huang\.gradle\wrapper\dists\gradle-8.10.2-bin\a04bxjujx95o3nb99gddekhwo\gradle-8.10.2\bin\gradle.bat"
DIST = os.path.join(ROOT, "dist")


def proxy_args():
    """把 HTTP(S)_PROXY 环境变量转成 Gradle 认的 -D 系统属性

    ⚠ 千万不要在这里塞 `-Dhttp.nonProxyHosts=localhost|127.0.0.1`：
    gradle.bat 是 cmd 批处理，参数里的 `|` 会被当成**管道符**，把命令从中间截断，
    报出来的是「'127.0.0.1' 不是内部或外部命令」—— 完全看不出跟代理有关。
    这里生成的 4 个 -D 参数只含 = . 数字，没有 cmd 元字符，才是安全的。
    """
    out = []
    for scheme in ("https", "http"):
        raw = os.environ.get(scheme.upper() + "_PROXY") or os.environ.get(scheme + "_proxy")
        if not raw:
            continue
        u = urllib.parse.urlparse(raw if "://" in raw else "http://" + raw)
        if not u.hostname:
            continue
        out += ["-D%s.proxyHost=%s" % (scheme, u.hostname),
                "-D%s.proxyPort=%s" % (scheme, u.port or 80)]
        if u.username:
            out += ["-D%s.proxyUser=%s" % (scheme, u.username),
                    "-D%s.proxyPassword=%s" % (scheme, u.password or "")]
    return out


def run(cmd, cwd, tag):
    print("\n" + "=" * 78)
    print("[%s] %s" % (tag, " ".join(cmd[:6]) + (" ..." if len(cmd) > 6 else "")))
    print("=" * 78)
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    tail = (p.stdout or "")[-4000:]
    if p.stderr and p.stderr.strip():
        # Gradle 的进度条走 stderr，只在失败时才有意义
        if p.returncode:
            tail += "\n--- stderr ---\n" + p.stderr[-2000:]
    print(tail)
    print("[%s] %s" % (tag, "OK" if p.returncode == 0 else "FAILED(rc=%d)" % p.returncode))
    return p.returncode


def newest(pattern_dir, prefix):
    if not os.path.isdir(pattern_dir):
        return None
    hits = [os.path.join(pattern_dir, f) for f in os.listdir(pattern_dir)
            if f.startswith(prefix) and f.endswith(".jar") and ".bak" not in f]
    if not hits:
        return None
    hits.sort(key=os.path.getmtime, reverse=True)
    return hits[0]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    want = set(args) if args else {"forge", "fabric", "neoforge"}
    skip_tests = "--skip-tests" in sys.argv
    os.makedirs(DIST, exist_ok=True)

    results = {}
    prox = proxy_args()
    if len(prox) > 1:
        print("检测到代理，转成 Gradle 参数: %s" % " ".join(prox[:4]))

    # ---------------- 1.8.9 / 1.12.2 coremod ----------------
    if "forge" in want:
        env_note = ""
        if skip_tests:
            env_note = "（--skip-tests 只影响下面这条，自检仍在 build.py 内部）"
        rc = run([PY, "build.py"], ROOT, "forge-1.8.9" + env_note)
        results["Forge coremod (1.8.9 / 1.12.2)"] = rc
        j = newest(DIST, "FrameSpike-Forge-")
        if j:
            print("   产物:", j, os.path.getsize(j), "B")

    # ---------------- Fabric ----------------
    if "fabric" in want:
        rc = run([GRADLE, "build", "--console=plain", "--no-daemon"] + prox,
                 os.path.join(ROOT, "fabric"), "Fabric")
        results["Fabric (1.16.5+ / 1.20.1)"] = rc
        j = newest(os.path.join(ROOT, "fabric", "build", "libs"), "FrameSpike-Fabric-")
        if j:
            dst = os.path.join(DIST, os.path.basename(j))
            shutil.copyfile(j, dst)
            print("   产物:", dst, os.path.getsize(dst), "B")

    # ---------------- NeoForge ----------------
    if "neoforge" in want:
        rc = run([GRADLE, "build", "--console=plain", "--no-daemon"] + prox,
                 os.path.join(ROOT, "neoforge"), "NeoForge")
        results["NeoForge (1.20.1+)"] = rc
        j = newest(os.path.join(ROOT, "neoforge", "build", "libs"), "FrameSpike-NeoForge-")
        if j:
            dst = os.path.join(DIST, os.path.basename(j))
            shutil.copyfile(j, dst)
            print("   产物:", dst, os.path.getsize(dst), "B")

    # ---------------- hook 校验 ----------------
    print()
    run([PY, os.path.join(ROOT, "out", "verify_hooks.py")], ROOT, "verify-hooks")

    # ---------------- 汇总 ----------------
    print("\n" + "=" * 78)
    print("汇总")
    print("=" * 78)
    for k, v in results.items():
        print("  %-34s %s" % (k, "OK" if v == 0 else "FAILED(rc=%d)" % v))
    print()
    jars = sorted(f for f in os.listdir(DIST) if f.endswith(".jar") and ".bak" not in f)
    print("dist/ 下的产物:")
    for f in jars:
        print("  %10d B  %s" % (os.path.getsize(os.path.join(DIST, f)), f))
    bad = [k for k, v in results.items() if v != 0]
    print()
    print("结果:", "全部成功" if not bad else ("失败: " + ", ".join(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
