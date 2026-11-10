"""Offline EGL check of the shipped row-slice shader, not a Minecraft client test."""
import json
import struct
import sys
from pathlib import Path

import moderngl
from io import BytesIO
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools/models"))
from prepare import table as model_table


source = Path(sys.argv[1])
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
ctx = moderngl.create_standalone_context(backend="egl", require=330)
vertex_include = (source / "vertex.glsl").read_text()
fragment_include = (source / "fragment.glsl").read_text()
vertex = """#version 330
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
const mat4 ProjMat = mat4(1./128.,0,0,0, 0,-1./128.,0,0, 0,0,1,0, -1,1,0,1);
const mat4 ModelViewMat = mat4(1.0);
""" + vertex_include + """
void main() {
    vec3 position = Position;
    vec2 uv = UV0;
    vertexColor = Color;
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0.0);
    dialogmenuImageCoord = vec2(0.0);
    dialogmenuSlice = vec3(0.0);
    dialogmenu_transform(position, uv);
    gl_Position = vec4(position.x / 128.0 - 1.0, 1.0 - position.y / 128.0, 0.0, 1.0);
    texCoord0 = uv;
}
"""
fragment = """#version 330
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
flat in int dialogmenuMarked;
flat in vec4 dialogmenuImagePixels;
in vec2 dialogmenuImageCoord;
in vec3 dialogmenuSlice;
out vec4 fragColor;
""" + fragment_include + """
void main() {
    vec4 pixel = texture(Sampler0, dialogmenu_uv());
    if (dialogmenuMarked == 1) pixel.rgb = vec3(1.0);
    fragColor = pixel * vertexColor;
    if (fragColor.a < 0.1) discard;
}
"""
program = ctx.program(vertex_shader=vertex, fragment_shader=fragment)
frame = ctx.simple_framebuffer((256, 256), components=4)
frame.use()
checks = 0


def draw(texture, payload, row, bounds, uv):
    rgba = tuple((payload >> shift & 255) / 255 for shift in (16, 8, 0, 24))
    left, top, right, bottom = bounds
    u0, v0, u1, v1 = uv
    # Match the real mixed GUI/text-buffer regression: the text draw starts at vertex3.
    base = 3
    data = [0.0] * (base * 9)
    for x, y, u, v in (
        (left, top, u0, v0), (left, bottom, u0, v1),
        (right, bottom, u1, v1), (right, top, u1, v0),
    ):
        data.extend((x, row * 9 + y, 0, *rgba, u, v))
    buffer = ctx.buffer(struct.pack(f"{len(data)}f", *data))
    indices = ctx.buffer(struct.pack("6I", *(base + index for index in (0, 1, 2, 0, 2, 3))))
    vao = ctx.vertex_array(program, [(buffer, "3f 4f 2f", "Position", "Color", "UV0")], indices)
    texture.use()
    vao.render()
    vao.release()
    indices.release()
    buffer.release()


def pixels():
    raw = frame.read(components=4)
    return [raw[y * 1024:(y + 1) * 1024] for y in reversed(range(256))]


def check(expected, label):
    global checks
    actual = pixels()
    for y in range(256):
        for x in range(256):
            pixel = tuple(actual[y][x * 4:x * 4 + 4])
            if pixel != expected(x, y):
                raise AssertionError(f"{label}: {x},{y} actual={pixel} expected={expected(x, y)}")
    checks += 1


def slices(top, height):
    y = top
    while y < top + height:
        band = y // 9
        bottom = min(top + height, (band + 1) * 9)
        yield max(0, band - 1), 0 if band == 0 else 1
        y = bottom


ink = ctx.texture((5, 8), 4, bytes([23, 71, 131, 255]) * 40)
ink.filter = moderngl.NEAREST, moderngl.NEAREST
for top in (0, 1, 7, 8, 9, 10, 17, 18, 19, 65, 100, 151):
    frame.clear()
    for row, band in slices(top, 8):
        payload = 0x20000000 | band << 28 | 7 << 18 | ((top - row * 9) & 1023) << 8
        # Native bitmap top is7, plus its shadow pass offset1.
        draw(ink, payload, row, (21, 8, 26, 16), (.0001, .0001, .9999, .9999))
        draw(ink, 0xFFBEEF01, row, (20, 7, 25, 15), (.0001, .0001, .9999, .9999))
    check(lambda x, y: (255, 255, 255, 255) if 20 <= x < 25 and top <= y < top + 8
          else (0, 0, 0, 0), f"text top={top}")
    for row, band in slices(top, 8):
        frame.clear()
        payload = 0x20000000 | band << 28 | 7 << 18 | ((top - row * 9) & 1023) << 8
        draw(ink, payload, row, (21, 8, 26, 16), (.0001, .0001, .9999, .9999))
        check(lambda x, y: (255, 255, 255, 255) if 20 <= x < 25 and top <= y < top + 8
              and row * 9 + band * 9 <= y < row * 9 + band * 9 + 9
              else (0, 0, 0, 0), f"isolated slice top={top} row={row} band={band}")

# A wrapper's four marker corners, matching RgbaImages.wrap.
size, sw, sh = 18, 16, 16
raw = bytearray(size * size * 4)
for y in range(sh):
    for x in range(sw):
        at = ((y + 1) * size + x + 1) * 4
        raw[at:at + 4] = bytes((x * 15, y * 15, 200, 255))
for index, (x, y) in enumerate(((0, 0), (0, size - 1), (size - 1, size - 1), (size - 1, 0))):
    at = (y * size + x) * 4
    raw[at:at + 4] = bytes((29, 83, 149 + index, size - 1))
    at = (y * size + (1 if x == 0 else x - 1)) * 4
    raw[at:at + 4] = bytes((sw, sh, 177, 0))
image = ctx.texture((size, size), 4, bytes(raw))
image.filter = moderngl.NEAREST, moderngl.NEAREST
for top in (0, 5, 8, 9, 10, 17, 35, 92):
    w, h = 143, 37
    frame.clear()
    for row, band in slices(top, h):
        payload = 0x20000000 | band << 28 | (w - 1) << 19 | (h - 1) << 10 | ((top - row * 9) & 1023)
        draw(image, payload, row, (21, 8, 22, 9), (.0001, .0001, .9999, .9999))
    def expected(x, y):
        if not (20 <= x < 20 + w and top <= y < top + h):
            return 0, 0, 0, 0
        sx = int((x - 20 + .5) * sw / w - .0001)
        sy = int((y - top + .5) * sh / h - .0001)
        return sx * 15, sy * 15, 200, 255
    check(expected, f"image top={top}")

# Direct external bitmap is not converted into a wrapper.
direct = ctx.texture((5, 8), 4, bytes([255, 255, 255, 255]) * 40)
direct.filter = moderngl.NEAREST, moderngl.NEAREST
for top in (0, 1, 8, 9, 15, 64, 151):
    frame.clear()
    for row, band in slices(top, 8):
        local = top - row * 9
        encoded = local + 32 if band == 0 else local
        payload = 0xC0000000 | 7 << 19 | (encoded & 2047) << 8
        draw(direct, payload, row, (21 - 65536, 8, 26 - 65536, 16), (.0001, .0001, .9999, .9999))
        draw(direct, 0xFFBEEF01, row, (20 - 65536, 7, 25 - 65536, 15), (.0001, .0001, .9999, .9999))
    check(lambda x, y: (255, 255, 255, 255) if 20 <= x < 25 and top <= y < top + 8
          else (0, 0, 0, 0), f"direct bitmap top={top}")

# Keep the native object geometry and per-face transform, including its -1px baseline.
model_include = (source / "model_vertex.glsl").read_text().replace(
    "layout(location = 7) flat out", "flat out").replace("gl_VertexIndex", "gl_VertexID")
geometry = (0,0,0,0, 0,1,0,0, 1,1,0,0, 1,0,0,0, 1,1,1,1)
model_texture = ctx.texture((16, 16), 4, bytes([255] * 16 * 16 * 4))
model_texture.filter = moderngl.NEAREST, moderngl.NEAREST
table = ("#define IS_GUI\n#define gl_BaseVertex 3\n"
         + model_table([geometry], "vanilla").decode() + model_table([], "ce").decode())
model_vertex = vertex.replace("void main() {", table + model_include + "\nvoid main() {")
model_vertex = model_vertex.replace("    dialogmenu_transform(position, uv);",
                                   "    dialogmenu_transform(position, uv);\n    dialogmenu_model(position, uv);")
program = ctx.program(vertex_shader=model_vertex, fragment_shader=fragment)
for center in (1, 8, 9, 10, 18, 35, 65, 100):
    frame.clear()
    for row, band in slices(center - 1, 16):
        payload = 0x40000000 | band << 28 | 15 << 19 | 15 << 10 | ((center - row * 9) & 1023)
        # AtlasGlyphProvider: top=row-1, shadow offset=1.
        draw(model_texture, payload, row, (21 - 66000, 0, 29 - 66000, 8), (0, 0, 1, 1))
        draw(model_texture, 0xFFBEEF01, row, (20 - 66000, -1, 28 - 66000, 7), (0, 0, 1, 1))
    check(lambda x, y: (255, 255, 255, 255) if 20 <= x < 36 and center - 1 <= y < center + 15
          else (0, 0, 0, 0), f"model center={center}")

for width, height in ((43, 16), (16, 51), (1, 1), (213, 153)):
    for top in (0, 8, 9, 47):
        frame.clear()
        for row, band in slices(top, height):
            payload = 0x60000000 | band << 28 | (width - 1) << 19 | (height - 1) << 10 | ((top - row * 9) & 1023)
            draw(model_texture, payload, row, (21 - 65536, 0, 29 - 65536, 8), (0, 0, 1, 1))
            draw(model_texture, 0xFFBEEF01, row, (20 - 65536, -1, 28 - 65536, 7), (0, 0, 1, 1))
        check(lambda x, y: (255, 255, 255, 255) if 20 <= x < 20 + width and top <= y < top + height
              else (0, 0, 0, 0), f"native inventory {width}x{height} top={top}")

(output / "gpu-result.json").write_text(json.dumps({
    "checks": checks, "renderer": ctx.info["GL_RENDERER"],
    "scope": "Shipped GLSL row clipping, texel sampling and hidden normal pass; offline EGL, not client acceptance",
}, indent=2) + "\n")
print(f"PASS offline GPU shader checks={checks} renderer={ctx.info['GL_RENDERER']}")
