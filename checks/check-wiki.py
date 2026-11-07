#!/usr/bin/env python3
"""Extract Wiki examples and verify them against the built RGBA JAR (Java 25)."""
import argparse
import base64
from html.parser import HTMLParser
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


class Examples(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.blocks = []
        self.active = None
        self.image = None

    def handle_starttag(self, tag, pairs):
        attrs = dict(pairs)
        if tag == "code" and ("data-yaml" in attrs or "data-java" in attrs):
            self.active = (attrs, [])
        if attrs.get("id") == "download-image":
            self.image = attrs["href"]

    def handle_data(self, text):
        if self.active is not None:
            self.active[1].append(text)

    def handle_endtag(self, tag):
        if tag == "code" and self.active is not None:
            self.blocks.append((self.active[0], "".join(self.active[1])))
            self.active = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, help="Paper 26.3 and runtime dependency classpath")
    parser.add_argument("--out", type=Path, default=ROOT / "build/wiki-checks")
    args = parser.parse_args()
    dependencies = args.classpath_file.read_text().strip() if args.classpath_file else os.environ["DIALOGMENU_LOCAL_CLASSPATH"]
    java_home = Path(os.environ["JAVA_HOME"])
    out = args.out.resolve()
    layouts = out / "examples"
    classes = out / "classes"
    layouts.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    examples = Examples()
    examples.feed((ROOT / "docs/index.html").read_text())
    java_files = []
    names = set()
    for attrs, text in examples.blocks:
        if "data-yaml" in attrs:
            name = attrs["data-yaml"]
            names.add(name + ".yml")
            (layouts / (name + ".yml")).write_text(text)
        else:
            path = out / (attrs["data-java"] + ".java")
            path.write_text(text)
            java_files.append(path)
    # Remove only stale examples produced in this check's output directory.
    for stale in layouts.glob("*.yml"):
        if stale.name not in names:
            stale.unlink()
    image = base64.b64decode(examples.image.split(",", 1)[1], validate=True)
    (layouts / "images").mkdir(exist_ok=True)
    (layouts / "images/demo-panel.png").write_bytes(image)
    jars = list((ROOT / "build/libs").glob("DialogMenu-rgba-*.jar"))
    if len(jars) != 1:
        raise ValueError("Build exactly one RGBA JAR first")
    cp = os.pathsep.join((str(jars[0]), dependencies))
    commands = [
        [str(java_home / "bin/javac"), "--release", "25", "-encoding", "UTF-8",
         "-cp", cp, "-d", str(classes), str(ROOT / "checks/WikiExampleChecks.java"),
         *map(str, java_files)],
        [str(java_home / "bin/java"), "-Xmx1536m", "-Djava.awt.headless=true",
         "-cp", os.pathsep.join((str(classes), cp)), "dev.dialogmenu.WikiExampleChecks", str(layouts)],
    ]
    log = out / "java-checks.log"
    with log.open("w") as stream:
        for command in commands:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                raise SystemExit(f"FAIL: {Path(command[0]).name}; see {log}")
    print(log.read_text()[-2500:])
    print(f"Java API example compiled against the actual JAR. Log: {log}")


if __name__ == "__main__":
    main()
