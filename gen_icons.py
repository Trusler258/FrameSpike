# 生成 README.md 用的 SVG 图标（24x24，统一线宽，无外链无字体）
import os

OUT = r"C:\Users\Huang\WorkBuddy\2026-10-05-03-06-50\assets\icons"
os.makedirs(OUT, exist_ok=True)

CY = "#3aa9d8"   # 主色（浅色/深色主题都能看清）
AM = "#d9822b"   # 警示
OK = "#4a9d5f"   # 通过

def svg(body, color=CY):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" '
            'fill="none" stroke="' + color + '" stroke-width="1.7" stroke-linecap="round" '
            'stroke-linejoin="round">\n  ' + body + '\n</svg>\n')

I = {
 # 指令
 "start":   '<circle cx="12" cy="12" r="8.2"/><circle cx="12" cy="12" r="3.2" fill="' + CY + '" stroke="none"/>',
 "stop":    '<rect x="6" y="6" width="12" height="12" rx="2.2"/>',
 "gauge":   '<path d="M3.5 16.5a8.5 8.5 0 0 1 17 0"/><path d="M12 16.5l4.4-5.4"/>',
 "probe":   '<circle cx="11" cy="11" r="6.2"/><path d="M15.6 15.6L21 21"/>',
 "mark":    '<path d="M5 3.5v17"/><path d="M5 5h12.5l-2.2 4 2.2 4H5"/>',
 "tail":    '<rect x="3.5" y="5.5" width="17" height="13" rx="2"/><path d="M7 10h10M7 14h6"/>',
 "slider":  '<path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2.2" fill="#fff"/><circle cx="15" cy="12" r="2.2" fill="#fff"/><circle cx="8" cy="17" r="2.2" fill="#fff"/>',
 "chat":    '<path d="M4 5.5h16v10.5H9l-5 4z"/>',
 "chatmin": '<path d="M4 5.5h16v10.5H9l-5 4z"/><path d="M8 11.5h8"/>',
 "glfinish":'<circle cx="12" cy="12" r="8.2"/><path d="M12 7.2v5l3.2 2.2"/>',
 "cpu":     '<rect x="7" y="7" width="10" height="10" rx="2"/><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/>',
 "help":    '<circle cx="12" cy="12" r="9"/><path d="M9.6 9.6a2.5 2.5 0 1 1 3.2 2.4c-.7.3-.7 1.1-.7 1.6"/><path d="M12 16.8h.01"/>',
 # 归因分类
 "write":   '<path d="M4 20l4-1 10-10-3-3L5 16z"/><path d="M14 6l3 3"/>',
 "classload":'<path d="M12 3l8 4v10l-8 4-8-4V7z"/><path d="M4 7l8 4 8-4M12 11v10"/>',
 "window":  '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M3 8.5h18"/>',
 "gl":      '<circle cx="12" cy="12" r="4"/><path d="M12 2.2v3M12 18.8v3M2.2 12h3M18.8 12h3M5.2 5.2l2 2M16.8 16.8l2 2M18.8 5.2l-2 2M7.2 16.8l-2 2"/>',
 "sound":   '<path d="M3 12h2l2-6.5 3 13 3-9.5 2 5 2-3h4"/>',
 "font":    '<path d="M5 19l7-14 7 14"/><path d="M8.6 13h6.8"/>',
 "map":     '<path d="M4 6l6-2 4 2 6-2v14l-6 2-4-2-6 2z"/><path d="M10 4v14M14 6v14"/>',
 "net":     '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3c4 5.2 4 12.8 0 18-4-5.2-4-12.8 0-18z"/>',
 # 状态
 "shield":  '<path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"/><path d="M9 12l2 2 4-4"/>',
 "warn":    '<path d="M12 4l9 16H3z"/><path d="M12 10v4M12 17h.01"/>',
 "clock":   '<circle cx="12" cy="12" r="8.2"/><path d="M12 7.5v4.5l3 2"/>',
 "tag":     '<path d="M12 3H5v7l9 9 7-7z"/><circle cx="8" cy="6.2" r="1.4" fill="' + CY + '" stroke="none"/>',
 "pen":     '<path d="M4 20l4-1 10-10-3-3L5 16z"/><path d="M14 6l3 3"/>',
 "code":    '<path d="M9 7l-5 5 5 5M15 7l5 5-5 5"/>',
 "flow":    '<path d="M3 12h5l2-4.5 4 9 2-4.5h5"/>',
}

n = 0
for name, body in I.items():
    open(os.path.join(OUT, name + ".svg"), "w", encoding="utf-8").write(svg(body))
    n += 1
# 两个带色的
open(os.path.join(OUT, "shield.svg"), "w", encoding="utf-8").write(svg(I["shield"], OK))
open(os.path.join(OUT, "warn.svg"), "w", encoding="utf-8").write(svg(I["warn"], AM))
print("写出 %d 个 SVG -> %s" % (n, OUT))
for f in sorted(os.listdir(OUT)):
    print("   ", f, os.path.getsize(os.path.join(OUT, f)), "bytes")
