"""Real face/textures reference versus production model GLSL, without a Minecraft client."""
from io import BytesIO
import json
from pathlib import Path
import struct
import sys

import moderngl
from PIL import Image, ImageChops

root, registry, ce_pack, output = map(Path, sys.argv[1:5])
fault = "--fault" in sys.argv[5:]
output.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(root / "tools/models"))
from item_catalog import ItemCatalog
from model_cache import PreparedCatalog
from model_catalog import path_for
from prepare import entry, table, allocate

ctx = moderngl.create_standalone_context(backend="egl", require=330)
frame = ctx.simple_framebuffer((256, 256), components=4)
frame.use()
source = root / "src/main/resources/rgba"
fragment = """#version 330
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
flat in int dialogmenuMarked;
flat in vec4 dialogmenuImagePixels;
in vec2 dialogmenuImageCoord;
in vec3 dialogmenuSlice;
out vec4 fragColor;
""" + (source / "fragment.glsl").read_text() + """
void main() {
    fragColor = texture(Sampler0, dialogmenu_uv()) * vertexColor;
    if (fragColor.a < 0.1) discard;
}
"""
header = """#version 330
#define IS_GUI
uniform sampler2D Sampler0;
in vec3 Position;
in vec4 Color;
in vec2 UV0;
out vec4 vertexColor;
out vec2 texCoord0;
flat out int dialogmenuMarked;
flat out vec4 dialogmenuImagePixels;
out vec2 dialogmenuImageCoord;
out vec3 dialogmenuSlice;
vec3 dialogmenu_color(int index) { return vec3(1.0); }
uniform mat4 ProjMat;
uniform mat4 ModelViewMat;
uniform int testBaseVertex;
#define gl_BaseVertex testBaseVertex
"""
model_shader = (source / "model_vertex.glsl").read_text().replace("layout(location = 7) flat out", "flat out").replace("gl_VertexIndex", "gl_VertexID")
if fault:
    model_shader = model_shader.replace("int localY =", "size.y *= 0.75;\n        int localY =")
candidate_body = """
void main() {
    vec3 position = Position;
    vec2 uv = UV0;
    vertexColor = Color;
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0.0);
    dialogmenuImageCoord = vec2(0.0);
    dialogmenuSlice = vec3(0.0);
    dialogmenu_transform(position, uv);
    dialogmenu_model(position, uv);
    gl_Position = vec4(position.x / 128.0 - 1.0, 1.0 - position.y / 128.0, 0.0, 1.0);
    texCoord0 = uv;
}
"""
reference = ctx.program(vertex_shader="""#version 330
in vec2 point;
in vec2 uv;
uniform vec2 size;
uniform vec4 tint;
out vec2 texCoord0;
out vec4 vertexColor;
void main() {
    vec2 position = vec2(128.0, 127.0) + point * size;
    gl_Position = vec4(position.x / 128.0 - 1.0, 1.0 - position.y / 128.0, 0.0, 1.0);
    texCoord0 = uv;
    vertexColor = tint;
}
""", fragment_shader="""#version 330
uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    fragColor = texture(Sampler0, texCoord0) * vertexColor;
    if (fragColor.a < 0.1) discard;
}
""")


def draw(program, values, format, attributes, base=0):
    buffer = ctx.buffer(struct.pack(f"{len(values)}f", *values))
    indices = ctx.buffer(struct.pack("6I", *(base + i for i in (0, 1, 2, 0, 2, 3))))
    vao = ctx.vertex_array(program, [(buffer, format, *attributes)], indices)
    vao.render()
    vao.release()
    indices.release()
    buffer.release()


def first_frame(image, width, height, sample):
    count = image.width // width * (image.height // height)
    def at(index):
        x, y = index % (image.width // width) * width, index // (image.width // width) * height
        return image.crop((x, y, x + width, y + height))
    return Image.blend(at(0), at(count - 1), sample)


catalog = ItemCatalog(ce_pack, registry=registry)
prepared = PreparedCatalog(catalog, output / "cache")
names = ("minecraft:stone", "minecraft:oak_stairs", "minecraft:chest",
         "minecraft:purple_shulker_box", "minecraft:red_banner", "minecraft:sea_lantern",
         "minecraft:player_head", "minecraft:conduit", "minecraft:decorated_pot",
         "minecraft:copper_golem_statue", "default:safe_block")
snapshots = {name: entry(prepared, name, "vanilla" if name.startswith("minecraft:") else "ce")
             for name in names}
tables, addresses = "", {}
for kind in ("vanilla", "ce"):
    records, models = allocate({name: value for name, value in snapshots.items()
                               if name.startswith("minecraft:") == (kind == "vanilla")}, [], 0 if kind == "vanilla" else 4096)
    tables += table(records, kind).decode()
    addresses.update(models)
candidate = ctx.program(vertex_shader=header + tables + (source / "vertex.glsl").read_text()
                        + model_shader + candidate_body, fragment_shader=fragment)
candidate["ProjMat"].write(struct.pack("16f", 1/128,0,0,0, 0,-1/128,0,0, 0,0,1,0, -1,1,0,1))
candidate["ModelViewMat"].write(struct.pack("16f", 1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1))
checks = []
try:
    for name in names:
        _, _, original = prepared.prepared(name)
        snapshot = snapshots[name]
        for size in ((64, 64), (91, 91), (29, 29)):
            for sample in (0, 0.5, 1):
                results = []
                for native, base, band in ((True, 0, 0), (False, 3, 0), (False, 7, 1)):
                    frame.clear()
                    for raw, part, address in zip(original, snapshot["parts"], addresses[name]):
                        width, height = catalog.sprite_frame(raw["sprite"])
                        png = catalog.archive.read(path_for(raw["sprite"], "textures", ".png"))
                        image = first_frame(Image.open(BytesIO(png)).convert("RGBA"), width, height, sample)
                        atlas = Image.new("RGBA", (1024, 1024))
                        atlas.paste(image, (17, 29))
                        texture = ctx.texture(atlas.size, 4, atlas.tobytes())
                        texture.filter = moderngl.NEAREST, moderngl.NEAREST
                        texture.use()
                        if native:
                            g = raw["geometry"]
                            reference["size"].value = size
                            reference["tint"].value = tuple(g[16:20])
                            values = []
                            for i in range(4):
                                values.extend((g[i*4], g[i*4+1],
                                    (17 + g[i*4+2] + (width if i >= 2 else 0)) / 1024,
                                    (29 + g[i*4+3] + (height if i in (1, 2) else 0)) / 1024))
                            draw(reference, values, "2f 2f", ("point", "uv"))
                        else:
                            for row in range(30):
                                baseline = row * 9 - band * 9
                                payload = 0x40000000 | band << 28 | (size[0]-1) << 11 | ((128-baseline) & 2047)
                                rgba = tuple((payload >> shift & 255) / 255 for shift in (16, 8, 0, 24))
                                values = [0.0] * (base * 9)
                                for x, y, u, v in ((129, 0, 17, 29), (129, 8, 17, 29+height),
                                    (137, 8, 17+width, 29+height), (137, 0, 17+width, 29)):
                                    values.extend((x - (address["face"] + 33) * 2000, baseline+y, 0, *rgba, u/1024, v/1024))
                                candidate["testBaseVertex"].value = base
                                draw(candidate, values, "3f 4f 2f", ("Position", "Color", "UV0"), base)
                        texture.release()
                    results.append(Image.frombytes("RGBA", (256, 256), frame.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM))
                label = name.replace(":", "-") + "-" + str(size) + "-" + str(sample)
                assert results[0].getbbox(), label + " reference empty"
                for image in results[1:]:
                    if image.tobytes() != results[0].tobytes():
                        results[0].save(output / (label + "-reference.png"))
                        image.save(output / (label + "-candidate.png"))
                        ImageChops.difference(results[0], image).save(output / (label + "-difference.png"))
                        raise AssertionError(label + " actual GLSL pixels differ")
                    checks.append(label)
                if size == (91, 37) and sample == 0:
                    results[1].save(output / (name.replace(":", "-") + ".png"))
finally:
    catalog.archive.close()
(output / "checks.json").write_text(json.dumps({"passed": len(checks), "failed": 0,
    "renderer": ctx.info["GL_RENDERER"], "scope": "offline EGL, real geometry/textures vs production GLSL; not player/FCL acceptance"}, indent=2))
print("PASS inventory model exact pixels=" + str(len(checks)))
