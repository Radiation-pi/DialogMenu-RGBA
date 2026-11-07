#!/usr/bin/env python3
"""将已校验的页面推送到本项目 Wiki，不强制覆盖远端历史。"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REMOTE = "git@github.com:palkane/DialogMenu-RGBA.wiki.git"


def main():
    subprocess.run([sys.executable, str(ROOT / "scripts/export-wiki.py"), "--check"], check=True)
    with tempfile.TemporaryDirectory(prefix="dialogmenu-wiki-") as temporary:
        checkout = Path(temporary) / "wiki"
        result = subprocess.run(["git", "clone", REMOTE, str(checkout)], capture_output=True, text=True)
        if result.returncode:
            print("无法克隆 Wiki。请先确认 GitHub 身份有写权限，并在以下页面创建第一篇 Wiki：", file=sys.stderr)
            print("https://github.com/palkane/DialogMenu-RGBA/wiki/_new", file=sys.stderr)
            return 1
        for source in (ROOT / "docs/wiki").rglob("*"):
            if source.is_file():
                destination = checkout / source.relative_to(ROOT / "docs/wiki")
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
        subprocess.run(["git", "add", "."], cwd=checkout, check=True)
        changed = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=checkout)
        if changed.returncode == 0:
            print("Wiki 已是最新内容，无需推送。")
            return 0
        subprocess.run(["git", "commit", "-m", "发布RGBA中文版Wiki"], cwd=checkout, check=True)
        subprocess.run(["git", "push", "origin", "HEAD"], cwd=checkout, check=True)
        print("Wiki 已发布：https://github.com/palkane/DialogMenu-RGBA/wiki")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
