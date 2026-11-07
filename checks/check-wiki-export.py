#!/usr/bin/env python3
"""检查 GitHub Wiki 的导航、示例和资源是否完整保留。"""
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
WIKI = "https://github.com/palkane/DialogMenu-RGBA/wiki/"


class CodeBlocks(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.active = None
        self.blocks = []

    def handle_starttag(self, tag, pairs):
        attrs = dict(pairs)
        if tag == "code" and ("data-yaml" in attrs or "data-java" in attrs):
            self.active = (attrs, [])

    def handle_data(self, text):
        if self.active:
            self.active[1].append(text)

    def handle_endtag(self, tag):
        if tag == "code" and self.active:
            self.blocks.append((self.active[0], "".join(self.active[1]).rstrip()))
            self.active = None


def main():
    parsed = CodeBlocks()
    parsed.feed((ROOT / "docs/index.html").read_text())
    target = ROOT / "docs/wiki"
    pages = list(target.glob("*.md"))
    assert len(pages) == 20, "应有18篇正文、侧栏、页脚"
    text = "\n".join(page.read_text() for page in pages)
    fences = re.findall(r"```[^\n]*\n([\s\S]*?)\n```", text)
    for attrs, block in parsed.blocks:
        assert block in fences, "Markdown 丢失或改变了代码示例"
        if "data-yaml" in attrs:
            example = target / "examples" / (attrs["data-yaml"] + ".yml")
            assert example.read_text() == block + "\n", "下载示例与正文不一致"
    links = 0
    for page in pages:
        for href in re.findall(r"\]\((https://github.com/palkane/DialogMenu-RGBA/wiki/[^)]+)\)", page.read_text()):
            name = unquote(href[len(WIKI):])
            assert (target / (name + ".md")).is_file(), "Wiki 链接目标不存在：" + name
            links += 1
    assert (target / "images/demo-panel.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), "缺少示例 PNG"
    assert "<script" not in text and "data:image" not in text, "Wiki 不应保留脚本或内嵌下载地址"
    print(f"通过：20份Markdown，{len(parsed.blocks)}份完整代码，{links}个页面链接，7份YAML和示例PNG；失败0")


if __name__ == "__main__":
    main()
