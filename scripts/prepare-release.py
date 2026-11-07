#!/usr/bin/env python3
"""Validate a tagged public release and assemble its source archive."""

from __future__ import annotations

import re
import shutil
import sys
import gzip
import io
import zipfile
from pathlib import Path
from editions import EDITIONS, SHADER_RESOURCES


ROOT = Path(__file__).resolve().parents[1]
DENIED = (
    "si" + "cilia",
    "\u897f\u897f\u91cc",
    "waste" + "city",
    "colo" + "nies",
    "v" + "chat",
    "alf" + "riends",
    "main" + "menushortcut",
    "main" + "-menu",
    "main" + "-features",
    "main" + "-economy",
    "main" + "-settings",
    "/home/" + "server",
    "/root" + "/",
    "pal" + "_kane",
)
PAGES = {"layout-demo.yml", "layout-detail.yml"}


def version_from_yaml(path: Path) -> str:
    return version_from_yaml_text(path.read_text())


def version_from_yaml_text(text: str) -> str:
    match = re.search(r"(?m)^version:\s*['\"]?([0-9]+\.[0-9]+\.[0-9]+)['\"]?\s*$", text)
    if not match:
        raise ValueError("Missing semantic version")
    return match.group(1)


def check_private(path: str, content: bytes) -> None:
    lowered_path = path.casefold()
    lowered_content = content.lower()
    for token in DENIED:
        if token.casefold() in lowered_path or token.encode() in lowered_content:
            raise ValueError(f"Private/server-specific content detected in {path}: {token}")


def check_archive(path: str, content: bytes) -> None:
    check_private(path, content)
    if path.lower().endswith(".zip") or path.lower().endswith(".jar"):
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Invalid embedded archive: {path}")
            for name in archive.namelist():
                check_archive(f"{path}!{name}", archive.read(name))
    elif path.lower().endswith(".gz"):
        check_archive(path[:-3], gzip.decompress(content))


def main(tag: str) -> None:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("Release tag must use vMAJOR.MINOR.PATCH, for example v1.2.3")
    version = tag[1:]

    properties = dict(
        line.split("=", 1)
        for line in (ROOT / "gradle.properties").read_text().splitlines()
        if line and not line.startswith("#") and "=" in line
    )
    if properties.get("version") != version:
        raise ValueError(f"gradle.properties version must match tag {tag}")
    plugin = ROOT / "src/main/resources/plugin.yml"
    if version_from_yaml_text(plugin.read_text().replace("${version}", version)) != version:
        raise ValueError("src/main/resources/plugin.yml version must match the Gradle version")

    built_jars = [validate_edition(version, edition) for edition in EDITIONS]

    pages = {path.name for path in (ROOT / "src/main/resources/layouts").glob("*.yml")}
    if pages != PAGES:
        raise ValueError(f"Public release must contain exactly {sorted(PAGES)}")

    files = [
        ROOT / "README.md",
        ROOT / "build.gradle",
        ROOT / "build.py",
        ROOT / "dependencies.lock.json",
        ROOT / "settings.gradle",
        ROOT / "gradle.properties",
        ROOT / "gradlew",
        ROOT / "gradlew.bat",
        ROOT / "scripts/prepare-release.py",
        ROOT / "scripts/editions.py",
        ROOT / ".github/workflows/release.yml",
    ]
    files.extend(sorted((ROOT / "gradle/wrapper").glob("*")))
    files.extend(sorted((ROOT / "src").rglob("*")))
    files = [path for path in files if path.is_file()]
    for path in files:
        relative = path.relative_to(ROOT).as_posix()
        check_archive(relative, path.read_bytes())

    dist = ROOT / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    for built_jar in built_jars:
        jar = dist / built_jar.name
        shutil.copy2(built_jar, jar)
        print(f"Prepared release JAR {jar.relative_to(ROOT)}.")
    output = dist / f"DialogMenu-{version}-source.zip"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            archive.write(path, Path(f"DialogMenu-{version}") / relative)
    print(f"Validated {tag}; prepared {output.relative_to(ROOT)} ({len(files)} files).")


def validate_edition(version: str, edition: str) -> Path:
    built_jar = ROOT / "build/libs" / f"DialogMenu-{edition}-{version}.jar"
    if not built_jar.is_file():
        raise ValueError(f"Missing Gradle build output: {built_jar.relative_to(ROOT)}")
    with zipfile.ZipFile(built_jar) as archive:
        if archive.testzip() is not None:
            raise ValueError(f"Invalid JAR: {built_jar.name}")
        jar_plugin = archive.read("plugin.yml").decode("utf-8")
        jar_layouts = {
            Path(name).name
            for name in archive.namelist()
            if name.startswith("layouts/") and not name.endswith("/")
        }
        if jar_layouts != PAGES:
            raise ValueError(f"{built_jar.name} must contain exactly the two public test layouts")
        if archive.read("dialogmenu-edition.properties") != f"edition={edition}\n".encode():
            raise ValueError(f"{built_jar.name} has the wrong renderer edition")
        shaders = {name for name in archive.namelist() if name.startswith("rgba/") and not name.endswith("/")}
        if shaders != (set(SHADER_RESOURCES) if edition == "rgba" else set()):
            raise ValueError(f"{built_jar.name} has incorrect shader resources")
        api = "26.3"
        if f"api-version: '{api}'" not in jar_plugin:
            raise ValueError(f"{built_jar.name} has an incorrect API floor")
    if version_from_yaml_text(jar_plugin) != version:
        raise ValueError(f"{built_jar.name} plugin.yml version must match version {version}")
    check_archive(built_jar.name, built_jar.read_bytes())
    return built_jar


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Usage: prepare-release.py vMAJOR.MINOR.PATCH")
        main(sys.argv[1])
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
