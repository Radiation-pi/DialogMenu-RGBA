from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from PIL import Image

from model_catalog import path_for
from prepare import build, LIMIT
from model_catalog import Unsupported


class SplitModelTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.output = self.root / "split"
        self.registry = self.root / "registry.json"
        self.pack = self.root / "ce.zip"
        self.items = {"test:one": {"mode": "auto"}, "test:two": {"mode": "auto"}}
        self.names = ["minecraft:stone", "minecraft:oak_stairs", "test:one", "test:two"]
        self.write_registry()
        self.write_source()

    def tearDown(self):
        self.temporary.cleanup()

    def write_registry(self):
        self.registry.write_text(json.dumps({"version": 1, "items": self.items}))

    def write_source(self, color="white", extra=None):
        buffer = BytesIO()
        Image.new("RGBA", (16, 16), color).save(buffer, format="PNG")
        with ZipFile(self.pack, "w") as archive:
            for name in ("one", "two"):
                archive.writestr(f"assets/test/items/{name}.json", json.dumps(
                    {"model": {"type": "minecraft:model", "model": "test:block/" + name}}))
                archive.writestr(f"assets/test/models/block/{name}.json", json.dumps(
                    {"parent": "minecraft:block/cube_all", "textures": {"all": "test:block/" + name}}))
                archive.writestr(f"assets/test/textures/block/{name}.png", buffer.getvalue())
            for name, data in (extra or {}).items():
                archive.writestr(name, data)

    def build(self, names=None):
        return build(names or self.names, self.pack, self.registry, self.output)

    def snapshot(self):
        return {path: (path.stat().st_mtime_ns, path.read_bytes())
                for path in self.output.rglob("*")
                if path.is_file() and path.name != "summary.json"}

    def test_repeat_changes_neither_pack_nor_entry_cache(self):
        self.build()
        before = self.snapshot()
        result = self.build()
        self.assertEqual(result["pack_changed"], {"vanilla": False, "ce": False})
        self.assertEqual(result["geometry_cache"]["vanilla"], {"reused": 2, "rebuilt": 0})
        self.assertEqual(result["geometry_cache"]["ce"], {"reused": 0, "rebuilt": 0})
        self.assertEqual(before, self.snapshot())

    def test_ce_registry_add_remove_modify_never_rewrites_vanilla(self):
        self.build()
        native = self.output / "vanilla/resourcepack.zip"
        before = native.stat().st_mtime_ns, native.read_bytes()
        state = json.loads((self.output / "ce/registry.json").read_text())
        unchanged_faces = state["models"]["test:two"]
        self.items["test:one"]["item"] = "minecraft:oak_stairs"
        self.write_registry()
        result = self.build()
        self.assertEqual(result["changes"]["ce"]["modified"], ["test:one"])
        self.assertEqual(result["changes"]["ce"]["reused"], ["test:two"])
        self.assertEqual(result["geometry_cache"]["ce"]["rebuilt"], 1)
        self.assertEqual(json.loads((self.output / "ce/registry.json").read_text())["models"]["test:two"],
                         unchanged_faces)
        self.assertEqual(result["pack_changed"], {"vanilla": False, "ce": True})
        del self.items["test:one"]
        self.write_registry()
        with self.assertRaises(Unsupported):
            self.build()
        remaining = [name for name in self.names if name != "test:one"]
        result = self.build(remaining)
        self.assertEqual(result["changes"]["ce"]["removed"], ["test:one"])
        self.assertNotIn("test:one", json.loads((self.output / "registry.json").read_text())["models"])
        self.assertEqual(self.build(remaining)["pack_changed"], {"vanilla": False, "ce": False})
        self.items["test:three"] = {"mode": "auto", "item": "minecraft:stone"}
        self.write_registry()
        result = self.build(remaining + ["test:three"])
        self.assertEqual(result["changes"]["ce"]["added"], ["test:three"])
        self.assertEqual(before, (native.stat().st_mtime_ns, native.read_bytes()))

    def test_unrequested_registration_does_not_touch_either_pack(self):
        self.build()
        self.items["test:not_referenced"] = {"mode": "auto"}
        self.write_registry()
        result = self.build()
        self.assertEqual(result["pack_changed"], {"vanilla": False, "ce": False})
        self.assertNotIn("test:not_referenced",
                         json.loads((self.output / "registry.json").read_text())["models"])

    def test_ce_source_only_change_waits_for_registration_revision(self):
        self.build()
        before = (self.output / "ce/resourcepack.zip").read_bytes()
        self.write_source("red")
        result = self.build()
        self.assertEqual(result["pack_changed"], {"vanilla": False, "ce": False})
        self.assertEqual((self.output / "ce/resourcepack.zip").read_bytes(), before)
        self.items["test:one"]["revision"] = 2
        self.write_registry()
        result = self.build()
        self.assertEqual(result["changes"]["ce"]["modified"], ["test:one"])
        self.assertEqual(result["changes"]["ce"]["reused"], ["test:two"])
        self.assertEqual(result["pack_changed"], {"vanilla": False, "ce": True})
        # The modified entry has new source pixels; the untouched entry keeps its snapshot.
        models = json.loads((self.output / "ce/registry.json").read_text())["models"]
        self.assertNotEqual(models["test:one"][0]["sprite"], models["test:two"][0]["sprite"])

    def test_vanilla_override_changes_only_vanilla_and_affected_model(self):
        self.build()
        ce = self.output / "ce/resourcepack.zip"
        before = ce.stat().st_mtime_ns, ce.read_bytes()
        override = {"parent": "minecraft:block/cube_all",
                    "textures": {"all": "minecraft:block/gold_block"},
                    "elements": [{"from": [0, 0, 0], "to": [16, 8, 16],
                                  "faces": {side: {"texture": "#all"} for side in
                                            ("down", "up", "north", "south", "east", "west")}}]}
        self.write_source(extra={path_for("minecraft:block/stone", "models"): json.dumps(override)})
        result = self.build()
        self.assertEqual(result["changes"]["vanilla"]["modified"], ["minecraft:stone"])
        self.assertEqual(result["changes"]["vanilla"]["reused"], ["minecraft:oak_stairs"])
        self.assertEqual(result["geometry_cache"]["vanilla"], {"reused": 1, "rebuilt": 1})
        self.assertEqual(result["pack_changed"], {"vanilla": True, "ce": False})
        self.assertEqual(before, (ce.stat().st_mtime_ns, ce.read_bytes()))

    def test_removal_drops_unreferenced_texture_and_keeps_remaining_ids(self):
        self.write_source("red")
        self.build()
        original = json.loads((self.output / "ce/registry.json").read_text())["models"]["test:two"]
        del self.items["test:one"]
        self.write_registry()
        result = self.build([name for name in self.names if name != "test:one"])
        self.assertEqual(result["geometry_cache"]["ce"]["rebuilt"], 0)
        self.assertEqual(json.loads((self.output / "ce/registry.json").read_text())["models"]["test:two"],
                         original)
        self.assertNotIn("test:one", json.loads((self.output / "ce/state.json").read_text())["entries"])
        # Both source textures were identical, so the one shared PNG remains, not a baked icon.
        with ZipFile(self.output / "ce/resourcepack.zip") as archive:
            pngs = [name for name in archive.namelist() if name.endswith(".png")]
            self.assertEqual(len(pngs), 1)
            with ZipFile(self.pack) as source:
                with Image.open(BytesIO(archive.read(pngs[0]))) as wrapped, \
                        Image.open(BytesIO(source.read("assets/test/textures/block/two.png"))) as original:
                    self.assertEqual(wrapped.size, (32, 32))
                    self.assertEqual(wrapped.crop((2, 2, 18, 18)).tobytes(), original.tobytes())

    def test_partition_addresses_and_shader_tables_match(self):
        self.build()
        registry = json.loads((self.output / "registry.json").read_text())["models"]
        for name, parts in registry.items():
            self.assertTrue(all((part["face"] < LIMIT) == name.startswith("minecraft:") for part in parts))
            if not name.startswith("minecraft:"):
                self.assertTrue(all(part["atlas"] == "minecraft:items" for part in parts))
        with ZipFile(self.output / "vanilla/resourcepack.zip") as native, \
                ZipFile(self.output / "ce/resourcepack.zip") as ce:
            self.assertNotIn("dialogmenu_26_3/assets/minecraft/shaders/core/text.vsh", ce.namelist())
            self.assertIn("assets/dialogmenu_vanilla/shaders/include/models.glsl", native.namelist())
            self.assertNotIn("assets/dialogmenu_ce/shaders/include/models.glsl", native.namelist())
            self.assertNotIn("MODEL_CE_FACES 0", ce.read(
                "assets/dialogmenu_ce/shaders/include/models.glsl").decode())


if __name__ == "__main__":
    unittest.main(verbosity=2)
