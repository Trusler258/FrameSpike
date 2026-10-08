# -*- coding: utf-8 -*-
"""本地 HTTP 反代：把 Gradle 对 maven.neoforged.net 的请求转发给 Python 直连。

背景（2026-10-08 踩坑）：maven.neoforged.net 的 CDN 对 Java TLS 指纹主动断握手
（"Remote host terminated the handshake"，3/3 稳定复现），Python（OpenSSL）却 3/3 通。
Gradle 走本地明文 HTTP 反代绕过 TLS 握手，反代用 Python 直连上游。
用法：python neoforge_proxy.py  然后仓库 URL 填 http://127.0.0.1:8765
"""
import http.server
import socketserver
import urllib.error
import urllib.request

UPSTREAM = "https://maven.neoforged.net/releases"
PORT = 8765

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PASS_HEADERS = ("content-type", "content-length", "last-modified", "etag", "location")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_HEAD(self):
        self.forward("HEAD")

    def do_GET(self):
        self.forward("GET")

    def forward(self, method):
        url = UPSTREAM + self.path
        try:
            req = urllib.request.Request(url, method=method, headers={"User-Agent": "neoforge-proxy"})
            with OPENER.open(req, timeout=120) as r:
                body = r.read() if method == "GET" else b""
                self.send_response(r.status)
                for k, v in r.headers.items():
                    if k.lower() in PASS_HEADERS:
                        self.send_header(k, v)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if body:
                    self.wfile.write(body)
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            self.send_header("Content-Length", "0")
            self.end_headers()
        except Exception:
            self.send_response(502)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    print("neoforge 反代: http://127.0.0.1:%d -> %s" % (PORT, UPSTREAM))
    Server(("127.0.0.1", PORT), Handler).serve_forever()
