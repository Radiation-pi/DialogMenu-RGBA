"""Namespaced inventory IDs over CE's generated resources and vanilla fallback."""
from collections import defaultdict
from copy import deepcopy
import json
import math
from pathlib import Path
import re
from zipfile import ZipFile

from model_catalog import Catalog, JAR, Unsupported, default_uv, item_transform, path_for, resource

CE_PACK = None
REGISTRY = Path("registry.json")
DIRECTIONS = ("down", "up", "north", "south", "west", "east")


def item_id(value):
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9/._-]+", value):
        raise Unsupported(f"Expected namespace:id, e.g. minecraft:stone or siciliaitems:al_ore: {value}")
    if any(part in ("", ".", "..") for part in value.split(":", 1)[1].split("/")):
        raise Unsupported(f"Invalid item ID: {value}")
    return value


class ResourceStack:
    def __init__(self, vanilla, pack):
        self.vanilla = ZipFile(vanilla)
        self.pack = ZipFile(pack) if pack is not None else None
        self.pack_names = set(self.pack.namelist()) if self.pack else set()
        self.names = set(self.vanilla.namelist()) | self.pack_names

    def read(self, path):
        return (self.pack if path in self.pack_names else self.vanilla).read(path)

    def close(self):
        self.vanilla.close()
        if self.pack:
            self.pack.close()


class ItemCatalog(Catalog):
    def __init__(self, pack=CE_PACK, jar=JAR, registry=REGISTRY):
        self.archive = ResourceStack(jar, pack)
        self.names = self.archive.names
        self.init_tracking()
        data = json.loads(Path(registry).read_text())
        if data.get("version") != 1 or not isinstance(data.get("items"), dict):
            raise Unsupported("Item registry requires version 1 and an items object")
        self.registrations = data["items"]
        for name, entry in self.registrations.items():
            item_id(name)
            if name.startswith("minecraft:") or not isinstance(entry, dict):
                raise Unsupported("Registry entries must be CE IDs with object definitions: " + name)
            if entry.get("mode", "auto") not in ("auto", "faces"):
                raise Unsupported("Registry mode must be auto or faces: " + name)
            if entry.get("mode") == "faces" and not entry.get("faces"):
                raise Unsupported("Manual face registration needs a nonempty faces list: " + name)

    def item_node(self, node, transforms=()):
        node = {**node, "type": resource(node["type"])}
        if (getattr(self, "_strict_dynamic", True)
                and node["type"] in ("minecraft:condition", "minecraft:select", "minecraft:range_dispatch")):
            raise Unsupported("Dynamic inventory model needs item-data selection: " + node["type"])
        return super().item_node(node, transforms)

    def block(self, name, overrides=None, inventory=True):
        name = item_id(name)
        if not inventory or overrides:
            raise Unsupported("ID display uses inventory models, not world block states")
        registration = self.registration(name)
        target = item_id(registration.get("item", name))
        if not self.exists(path_for(target, "items")):
            raise Unsupported("Unknown inventory item ID: " + name)
        if registration.get("mode") == "faces":
            return self.indexed_faces(target, registration), {
                "source": "inventory-indexed", "item": target,
                "selection": registration["faces"]}
        self._strict_dynamic = not name.startswith("minecraft:")
        try:
            return super().block(target)
        finally:
            self._strict_dynamic = True

    def registration(self, name):
        if name.startswith("minecraft:"):
            return {}
        if name not in self.registrations:
            raise Unsupported("CE ID is not registered in item_registry.json: " + name)
        return self.registrations[name]

    def describe_faces(self, name):
        """Discovery is read-only and does not register an item."""
        name = item_id(name)
        target = item_id(self.registrations.get(name, {}).get("item", name))
        nodes = self.item_node(self.json(path_for(target, "items"))["model"])
        result = []
        for node in nodes:
            if node["type"] != "minecraft:model":
                raise Unsupported("Face indexing currently requires JSON element models: " + name)
            model = resource(node["model"])
            if self.generated_item(model):
                raise Unsupported("2D inventory icon has no 3D element face indices: " + name)
            merged = self.model(model)
            for index, element in enumerate(merged.get("elements", [])):
                for direction, face in element.get("faces", {}).items():
                    row = {"model": model, "element": index, "face": direction,
                           "texture_reference": face.get("texture"),
                           "uv": face.get("uv", default_uv(
                               direction, element["from"], element["to"])),
                           "rotation": face.get("rotation", 0),
                           "from": element["from"], "to": element["to"]}
                    try:
                        row["texture"] = self.texture(face["texture"], merged["textures"])
                        row["texture_size"] = self.sprite_frame(row["texture"])
                    except (Unsupported, KeyError) as error:
                        row["texture_error"] = str(error)
                    result.append(row)
        return result

    def indexed_faces(self, target, registration):
        nodes = self.item_node(self.json(path_for(target, "items"))["model"])
        if any(node["type"] != "minecraft:model" for node in nodes):
            raise Unsupported("Manual face indices require ordinary JSON inventory models")
        available = {}
        for node in nodes:
            model = resource(node["model"])
            if self.generated_item(model):
                raise Unsupported("Cannot rebuild a 2D inventory icon with face indices")
            merged = self.model(model)
            for index, element in enumerate(merged.get("elements", [])):
                for direction in element.get("faces", {}):
                    available.setdefault((model, index, direction), []).append((node, merged))
        selected, seen = [], set()
        for selector in registration["faces"]:
            element, direction = selector.get("element"), selector.get("face")
            if type(element) is not int or element < 0 or direction not in DIRECTIONS:
                raise Unsupported("Face index requires nonnegative element and a face direction")
            model = selector.get("model")
            candidates = [key for key in available
                          if key[1:] == (element, direction)
                          and (model is None or key[0] == resource(model))]
            if len(candidates) != 1 or len(available[candidates[0]]) != 1:
                raise Unsupported(f"Missing or ambiguous face index: {selector}")
            key = candidates[0]
            if key in seen:
                raise Unsupported(f"Duplicate face index: {selector}")
            seen.add(key)
            node, merged = available[key][0]
            chosen = deepcopy(merged["elements"][element])
            raw_face = chosen["faces"][direction]
            if "texture" in selector:
                raw_face["texture"] = selector["texture"]
            if "uv" in selector:
                uv = selector["uv"]
                if (not isinstance(uv, list) or len(uv) != 4
                        or any(type(v) not in (int, float) or not math.isfinite(v) for v in uv)):
                    raise Unsupported("Face UV override requires [u0,v0,u1,v1] in model 0..16 units")
                raw_face["uv"] = uv
            chosen["faces"] = {direction: raw_face}
            selected.extend(item_transform(self.faces(
                key[0], node, model_data={**merged, "elements": [chosen]}), node["_transforms"]))
        return selected

    def pack_assets(self, faces):
        """Keep CE texture bytes intact and explicitly stitch referenced sprites."""
        files = {}
        atlases = defaultdict(set)
        for face in faces:
            path = path_for(face.sprite, "textures", ".png")
            if path not in self.archive.pack_names:
                continue
            files[path] = self.archive.read(path)
            if path + ".mcmeta" in self.names:
                files[path + ".mcmeta"] = self.archive.read(path + ".mcmeta")
            atlases[face.atlas].add(face.sprite)
        for atlas, sprites in atlases.items():
            atlas_path = path_for(atlas, "atlases")
            try:
                definition = json.loads(self.archive.read(atlas_path))
            except KeyError:
                definition = {"sources": []}
            existing = {
                source.get("sprite", source.get("resource"))
                for source in definition.get("sources", [])
                if source.get("type") == "minecraft:single"
            }
            definition["sources"].extend(
                {"type": "minecraft:single", "resource": sprite, "sprite": sprite}
                for sprite in sorted(sprites - existing)
            )
            files[atlas_path] = json.dumps(definition, separators=(",", ":")).encode()
        return files
