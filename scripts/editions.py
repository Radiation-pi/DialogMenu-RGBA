"""独立 RGBA 发行包的打包规则。"""
from pathlib import Path
import zipfile

EDITIONS = ("rgba",)
SHADER_RESOURCES = ("rgba/text.vsh", "rgba/text.fsh", "rgba/characters-3500.txt")


def edition_entries(entries: dict[str, bytes], edition: str) -> dict[str, bytes]:
    if edition not in EDITIONS:
        raise ValueError(f"Unknown edition: {edition}")
    result = dict(entries)
    result["dialogmenu-edition.properties"] = f"edition={edition}\n".encode()
    if not all(name in result for name in SHADER_RESOURCES):
        raise ValueError("RGBA distribution is missing shader resources")
    result["plugin.yml"] = result["plugin.yml"].replace(b"api-version: '26.2'", b"api-version: '26.3'")
    return result


def write_jar(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            jar.writestr(info, data)
