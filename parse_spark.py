import gzip, io, json, os, sys, collections

p = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\spark_raw.json"
raw = open(p, "rb").read()
print("magic:", raw[:4].hex(), "size:", len(raw))

data = raw
if raw[:2] == b"\x1f\x8b":
    data = gzip.decompress(raw)
    print("decompressed:", len(data))
try:
    d = json.loads(data.decode("utf-8"))
except Exception as e:
    print("json fail:", e)
    print(data[:300])
    sys.exit(1)

print("keys:", list(d.keys()))
meta = d.get("metadata", {})
print("METADATA:", json.dumps(meta, ensure_ascii=False)[:2000])
cm = d.get("classMap", {})
print("classMap:", len(cm))
threads = d.get("threads", [])
print("threads:", len(threads))
for t in threads:
    ch = t.get("children", [])
    top = ch[0] if ch else {}
    print("  -", t.get("name"), "time=", t.get("time"), "top=", top.get("className"), top.get("methodName"), top.get("times"))
