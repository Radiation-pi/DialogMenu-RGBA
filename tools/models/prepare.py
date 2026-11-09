"""Independent vanilla and opt-in CE model packs with per-entry updates."""
from copy import deepcopy
import json
from pathlib import Path

from item_catalog import CE_PACK, REGISTRY, ItemCatalog, item_id
from model_cache import PreparedCatalog, digest, write_changed, write_json_changed, write_pack_changed
from model_catalog import Unsupported, path_for
from corner_texture import PADDING, wrap
ROOT = Path(__file__).resolve().parent

LIMIT = 4096


def table(records, kind):
    values = records or [None]
    vectors = [record or [0.0] * 24 for record in values]
    return (f"#define MODEL_{kind.upper()}_FACES {len(records)}\n"
            f"const vec4 model{kind.title()}Data[{len(values) * 6}] = vec4[](\n"
            + ",\n".join("vec4(" + ",".join(f"{value:.6f}" for value in record[i:i + 4]) + ")"
                        for record in vectors for i in range(0, 24, 4)) + "\n);\n").encode()


def allocate(entries, previous, offset):
    used = {tuple(part["geometry"]) for entry in entries.values() for part in entry["parts"]}
    slots = [record if record is not None and tuple(record) in used else None
             for record in previous]
    addresses = {tuple(record): index for index, record in enumerate(slots) if record is not None}
    holes = iter(index for index, record in enumerate(slots) if record is None)
    for record in sorted(used - addresses.keys()):
        index = next(holes, len(slots))
        if index == len(slots):
            slots.append(list(record))
        else:
            slots[index] = list(record)
        addresses[record] = index
    while slots and slots[-1] is None:
        slots.pop()
    if len(slots) > LIMIT:
        raise Unsupported("Each model partition supports at most 4096 geometry records")
    models = {name: [{"atlas": part["atlas"], "sprite": part["sprite"],
                      "face": offset + addresses[tuple(part["geometry"])]}
                     for part in entry["parts"]]
              for name, entry in entries.items() if entry["report"]["status"] == "geometry"}
    return slots, models


def entry(catalog, name, kind):
    faces, source, parts = catalog.prepared(name)
    parts = deepcopy(parts)
    files, singles = {}, {}
    for part in parts:
        original = path_for(part["sprite"], "textures", ".png")
        png = catalog.archive.read(original)
        meta = (catalog.archive.read(original + ".mcmeta")
                if original + ".mcmeta" in catalog.names else None)
        key, tag, png, meta, (width, height, fw, fh) = wrap(png, meta, kind)
        sprite = "dialogmenu_" + kind + ":corners/" + key
        target = path_for(sprite, "textures", ".png")
        part["sprite"] = sprite
        if kind == "ce":
            part["atlas"] = "minecraft:items"
        for corner in range(4):
            part["geometry"][corner * 4 + 2] += PADDING - (fw - width) * (corner >= 2)
            part["geometry"][corner * 4 + 3] += PADDING - (fh - height) * (corner in (1, 2))
        part["geometry"].extend([*tag, 0.0])
        files[target] = png.hex()
        if meta is not None:
            files[target + ".mcmeta"] = meta.hex()
        singles.setdefault(part["atlas"], {})[sprite] = sprite
    status = "skipped" if "skip_reason" in source else "geometry" if faces else "empty"
    return {"registration": deepcopy(catalog.registration(name)), "parts": parts, "assets": files,
            "singles": singles,
            "report": {**source, "status": status, "faces": len(faces), "visible": len(parts)}}


def partition_files(entries, kind, vanilla_archive):
    files, atlases = {}, {}
    for snapshot in entries.values():
        for path, value in snapshot["assets"].items():
            data = bytes.fromhex(value)
            if path in files and files[path] != data:
                raise Unsupported("Conflicting cached texture contents: " + path)
            files[path] = data
        for atlas, sources in snapshot["singles"].items():
            atlases.setdefault(atlas, {}).update(sources)
    for atlas, sources in atlases.items():
        path = path_for(atlas, "atlases")
        if kind == "vanilla" and path in vanilla_archive.namelist():
            definition = json.loads(vanilla_archive.read(path))
        else:
            definition = {"sources": []}
        definition["sources"].extend({"type": "minecraft:single", "resource": value, "sprite": sprite}
                                     for sprite, value in sorted(sources.items()))
        files[path] = json.dumps(definition, separators=(",", ":")).encode()
    return files


def build(names, ce_pack, registry, output):
    names = list(dict.fromkeys(item_id(name) for name in names))
    output = Path(output)
    raw = ItemCatalog(ce_pack, registry=registry)
    old = {}
    for kind in ("vanilla", "ce"):
        path = output / kind / "state.json"
        old[kind] = json.loads(path.read_text()) if path.exists() else {"entries": {}, "slots": []}
    # A referenced but revoked ID must reject the candidate, not silently disappear.
    for name in names:
        raw.registration(name)
    compiler = digest(Path(__file__).read_bytes() + (ROOT / "model_cache.py").read_bytes()
                      + (ROOT / "model_catalog.py").read_bytes() + (ROOT / "item_catalog.py").read_bytes()
                      + (ROOT / "corner_texture.py").read_bytes())
    entries = {"vanilla": {}, "ce": {}}
    changes = {kind: {"added": [], "removed": [], "modified": [], "reused": []}
               for kind in entries}
    catalogs = {kind: PreparedCatalog(raw, output / kind / "model-cache") for kind in entries}
    try:
        for name in names:
            kind = "vanilla" if name.startswith("minecraft:") else "ce"
            previous = old[kind]["entries"].get(name)
            registration = raw.registration(name)
            if (kind == "ce" and previous and previous["registration"] == registration
                    and old[kind].get("compiler") == compiler):
                snapshot = previous
                changes[kind]["reused"].append(name)
            else:
                snapshot = entry(catalogs[kind], name, kind)
                change = "added" if previous is None else "reused" if snapshot == previous else "modified"
                changes[kind][change].append(name)
            if snapshot["report"]["status"] != "geometry":
                raise Unsupported(name + " is not a 3D inventory model; use an image for 2D icons")
            entries[kind][name] = snapshot
        for kind in entries:
            changes[kind]["removed"] = sorted(old[kind]["entries"].keys() - entries[kind].keys())
        tags = {}
        for side in entries.values():
            for snapshot in side.values():
                for part in snapshot["parts"]:
                    tag = tuple(part["geometry"][20:23])
                    previous = tags.setdefault(tag, part["sprite"])
                    if previous != part["sprite"]:
                        raise Unsupported("Model corner tag collision: " + previous + " / " + part["sprite"])
        metadata = {"pack": {"description": "DialogMenu inventory models", "pack_format": 97,
                             "min_format": [97, 0], "max_format": [97, 1]}}
        files = {kind: {"pack.mcmeta": json.dumps(metadata, separators=(",", ":")).encode()}
                 for kind in entries}
        slots, models = {}, {}
        for kind, offset in (("vanilla", 0), ("ce", LIMIT)):
            slots[kind], models[kind] = allocate(entries[kind], old[kind]["slots"], offset)
            files[kind].update(partition_files(entries[kind], kind, raw.archive.vanilla))
            files[kind][f"assets/dialogmenu_{kind}/shaders/include/models.glsl"] = table(slots[kind], kind)
        changed = {}
        for kind in entries:
            directory = output / kind
            changed[kind] = write_pack_changed(directory / "resourcepack.zip", files[kind])
            write_json_changed(directory / "state.json",
                               {"compiler": compiler, "entries": entries[kind], "slots": slots[kind]})
            write_json_changed(directory / "registry.json", {"models": models[kind]})
            write_changed(directory / "model_data.glsl",
                          files[kind][f"assets/dialogmenu_{kind}/shaders/include/models.glsl"])
        registrations = {name: value["registration"] for side in entries.values()
                         for name, value in side.items()}
        write_json_changed(output / "registry.json",
                           {"models": {**models["vanilla"], **models["ce"]}, "registrations": registrations,
                            "bounds": {
                                name: [min(p["geometry"][i] for p in value["parts"] for i in (0, 4, 8, 12)),
                                       min(p["geometry"][i] for p in value["parts"] for i in (1, 5, 9, 13)),
                                       max(p["geometry"][i] for p in value["parts"] for i in (0, 4, 8, 12)),
                                       max(p["geometry"][i] for p in value["parts"] for i in (1, 5, 9, 13))]
                                for side in entries.values() for name, value in side.items()}})
        write_json_changed(output / "catalog.json",
                           {name: value["report"] for side in entries.values() for name, value in side.items()})
        result = {"pack_changed": changed, "changes": changes,
                  "geometry_cache": {kind: value.cache_stats for kind, value in catalogs.items()},
                  "packs": {kind: str(output / kind / "resourcepack.zip") for kind in entries},
                  "scope": "Independent model data packs; core shader belongs to DialogMenu"}
        write_json_changed(output / "summary.json", result)
        return result
    finally:
        raw.archive.close()


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["build", "faces"], nargs="?", default="build")
    parser.add_argument("--ids", nargs="+", required=True)
    parser.add_argument("--ce-pack", type=Path)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == "faces":
            catalog = ItemCatalog(args.ce_pack, registry=args.registry)
            try:
                print(json.dumps({name: catalog.describe_faces(name) for name in args.ids}, indent=2))
            finally:
                catalog.archive.close()
        else:
            print(json.dumps(build(args.ids, args.ce_pack, args.registry, args.output), indent=2))
    except Unsupported as error:
        parser.exit(2, str(error) + "\n")
