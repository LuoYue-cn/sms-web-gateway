#!/usr/bin/env python3
"""Build the clean Baota (aaPanel) package from the current tree.

    cd web && npm run build && cd ..
    python3 server/tools/build_baota_zip.py [output.zip]

Only files that are explicitly listed here go into the zip: no tests, no local data, no node_modules, no secrets.
The layout (frontend in both the root and public/, root index.php fallback, empty data/) is documented in BAOTA_SETUP.md.
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SERVER = os.path.join(ROOT, "server")
DIST = os.path.join(ROOT, "web", "dist")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "dist", "smsgate-baota-clean.zip")

# 固定时间戳：同样的内容打出来的包字节完全一致，方便校验
FIXED_TIME = (2026, 1, 1, 0, 0, 0)


def walk(base, rel=""):
    for name in sorted(os.listdir(os.path.join(base, rel))):
        r = os.path.join(rel, name)
        if os.path.isdir(os.path.join(base, r)):
            yield from walk(base, r)
        else:
            yield r


def main():
    if not os.path.isfile(os.path.join(DIST, "index.html")):
        sys.exit("web/dist/index.html 不存在，请先在 web/ 里执行 npm run build")
    entries = {}

    def add(arc, path, data=None):
        entries[arc.replace(os.sep, "/")] = data if data is not None else open(path, "rb").read()

    # 服务端代码
    for sub in ("src", "migrations", "bin"):
        for r in walk(SERVER, sub):
            add(r, os.path.join(SERVER, r))
    for name in ("index.php", ".htaccess", "nginx.rewrite.conf", "README_BAOTA.md"):
        add(name, os.path.join(SERVER, name))
    add("API.md", os.path.join(ROOT, "docs", "API.md"))
    add("data/.gitkeep", None, b"")

    # public/：入口脚本 + 前端
    for r in walk(os.path.join(SERVER, "public")):
        if r not in ("index.html",):  # 以构建结果为准
            add(os.path.join("public", r), os.path.join(SERVER, "public", r))
    for r in walk(DIST):
        add(os.path.join("public", r), os.path.join(DIST, r))
        add(r, os.path.join(DIST, r))  # 根目录也放一份：运行目录没切到 public 时仍能打开页面

    banned = [n for n in entries if n.startswith(("tests/", "data/sessions")) or n.endswith((".sqlite", ".db", ".env", ".log", ".bak", ".map"))]
    if banned:
        sys.exit(f"打包内容异常：{banned[:5]}")
    for must in ("src/Kernel.php", "migrations/003_codes_and_stats.sql", "migrations/004_reports.sql", "bin/daily_report.php", "bin/backfill_codes.php", "public/index.php", "public/index.html", "index.html"):
        if must not in entries:
            sys.exit(f"缺少 {must}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for arc in sorted(entries):
            zi = zipfile.ZipInfo(arc, FIXED_TIME)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o755 if arc.startswith("bin/") else 0o644) << 16
            z.writestr(zi, entries[arc])
    print(f"{OUT}: {len(entries)} files, {os.path.getsize(OUT) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
