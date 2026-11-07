#!/usr/bin/env python3
"""将离线 HTML 手册导出为 GitHub Wiki，保持单一内容源。"""
import argparse
import base64
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
WIKI = "https://github.com/palkane/DialogMenu-RGBA/wiki"
RAW = "https://raw.githubusercontent.com/wiki/palkane/DialogMenu-RGBA/"


class Node:
    def __init__(self, tag="", attrs=()):
        self.tag, self.attrs, self.children = tag, dict(attrs), []

    def text(self):
        return "".join(child if isinstance(child, str) else child.text() for child in self.children)

    def find(self, tag):
        return next((node for node in self.walk() if node.tag == tag), None)

    def walk(self):
        for child in self.children:
            if isinstance(child, Node):
                yield child
                yield from child.walk()


class Document(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag not in {"meta", "link", "input", "br", "hr", "img", "path", "rect"}:
            self.stack.append(node)

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, 0, -1):
            if self.stack[index].tag == tag:
                del self.stack[index:]
                break

    def handle_data(self, text):
        self.stack[-1].children.append(text)


def export():
    parsed = Document()
    parsed.feed((ROOT / "docs/index.html").read_text())
    articles = [node for node in parsed.root.walk() if node.tag == "article"]
    names = {node.attrs["id"]: ("Home" if index == 0 else node.find("h1").text().replace(" ", "-"))
             for index, node in enumerate(articles)}
    files = {}

    def render(node):
        if isinstance(node, str):
            return re.sub(r"\s+", " ", node)
        tag, attrs = node.tag, node.attrs
        if tag in {"button", "script", "style", "noscript", "svg", "output"}:
            return ""
        if attrs.get("class") in {"code-head", "size-control", "article-nav"}:
            return ""
        if tag == "pre":
            code = node.find("code")
            text = code.text().rstrip()
            language = "yaml" if "data-yaml" in code.attrs or "data-config" in code.attrs else (
                "java" if "data-java" in code.attrs else "")
            download = ""
            if "data-yaml" in code.attrs:
                name = code.attrs["data-yaml"] + ".yml"
                files["examples/" + name] = (text + "\n").encode()
                download = f"[下载 {name}]({RAW}examples/{name})\n\n"
            return "\n\n```" + language + "\n" + text + "\n```\n\n" + download
        if tag == "code":
            return "`" + node.text().replace("`", "\\`") + "`"
        if tag in {"h1", "h2", "h3"}:
            return "\n\n" + "#" * int(tag[1]) + " " + node.text().strip() + "\n\n"
        if tag == "table":
            rows = []
            for row in node.walk():
                if row.tag != "tr":
                    continue
                cells = [inline(cell).replace("|", "\\|").replace("\n", " ")
                         for cell in row.children if isinstance(cell, Node) and cell.tag in {"td", "th"}]
                rows.append("| " + " | ".join(cells) + " |")
                if len(rows) == 1:
                    rows.append("| " + " | ".join("---" for _ in cells) + " |")
            return "\n\n" + "\n".join(rows) + "\n\n"
        if tag in {"ul", "ol"}:
            items = [child for child in node.children if isinstance(child, Node) and child.tag == "li"]
            return "\n\n" + "\n".join((f"{index}. " if tag == "ol" else "- ") + inline(child)
                                       for index, child in enumerate(items, 1)) + "\n\n"
        text = "".join(render(child) for child in node.children)
        if tag == "a":
            href = attrs.get("href", "")
            if href.startswith("#"):
                href = WIKI + "/" + quote(names[href[1:]])
            if attrs.get("id") == "download-image":
                files["images/demo-panel.png"] = base64.b64decode(href.split(",", 1)[1], validate=True)
                href = RAW + "images/demo-panel.png"
            return "[" + text.strip() + "](" + href + ")"
        if tag in {"strong", "b"}:
            return "**" + text.strip() + "**"
        if tag == "br":
            return "\n\n"
        if tag == "p":
            return "\n\n" + text.strip() + "\n\n"
        if tag == "div" and "note" in attrs.get("class", ""):
            return "\n\n" + "\n".join("> " + line for line in text.strip().splitlines()) + "\n\n"
        return text

    def inline(node):
        return re.sub(r"[ \t]+", " ", "".join(render(child) for child in node.children)).strip()

    for index, article in enumerate(articles):
        text = render(article).strip()
        # GitHub Wiki 不运行自定义 JavaScript，将交互示意改成静态说明。
        if article.attrs["id"] == "layout":
            text = text.replace("逻辑坐标示意，不是 Minecraft 客户端截图。",
                "坐标示意：中心 (0,0)，右侧 +X，上方 +Y。300×180 布局在 small / medium / large 下分别为 300×180、360×216、420×252。")
        nav = []
        for page, label in [(articles[index - 1] if index else None, "上一篇"),
                            (articles[index + 1] if index + 1 < len(articles) else None, "下一篇")]:
            if page:
                nav.append(f"{label}：[{page.find('h1').text()}]({WIKI}/{quote(names[page.attrs['id']])})")
        text = re.sub(r"\n[ \t]+\n", "\n\n", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        text = "\n".join(line.rstrip() for line in text.splitlines())
        files[names[article.attrs["id"]] + ".md"] = (text + "\n\n---\n\n" + " · ".join(nav) + "\n").encode()
    sidebar = ["## DialogMenu RGBA", ""]
    navigation = next(node for node in parsed.root.walk() if node.tag == "nav")
    for child in navigation.children:
        if not isinstance(child, Node):
            continue
        if child.tag == "p":
            sidebar += ["", "### " + child.text(), ""]
        elif child.tag == "a":
            name = names[child.attrs["href"][1:]]
            sidebar.append("- [" + child.text() + "](" + WIKI + "/" + quote(name) + ")")
    files["_Sidebar.md"] = ("\n".join(sidebar).strip() + "\n").encode()
    files["_Footer.md"] = ("DialogMenu RGBA 0.1.5 · 简体中文 · "
        "[项目源码](https://github.com/palkane/DialogMenu-RGBA) · "
        "[下载离线手册](https://github.com/palkane/DialogMenu-RGBA/blob/main/docs/index.html)\n").encode()
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="仅检查导出是否与内容源一致")
    args = parser.parse_args()
    target = ROOT / "docs/wiki"
    files = export()
    # 只清理生成目录中已取消的正文页，不碰其他文档目录。
    stale = [path for path in target.glob("*.md") if path.name not in files]
    if args.check and stale:
        raise SystemExit("Wiki 存在过期页面：" + ", ".join(path.name for path in stale))
    if not args.check:
        for path in stale:
            path.unlink()
    for name, content in files.items():
        path = target / name
        if args.check:
            if not path.is_file() or path.read_bytes() != content:
                raise SystemExit("Wiki 需要重新导出：" + name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(f"Wiki：18 篇正文、侧栏和页脚、7 份 YAML、1 张示例图片；{'检查通过' if args.check else '已导出'}")


if __name__ == "__main__":
    main()
