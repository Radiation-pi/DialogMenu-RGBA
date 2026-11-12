"""Render actual encoded primitive components and generated font atlases through production GLSL."""
import json
from pathlib import Path
import struct
import sys
import moderngl
import numpy as np
from PIL import Image

shader, fixtures, output = map(Path, sys.argv[1:4])
output.mkdir(parents=True, exist_ok=True)
pack = fixtures / "pack/assets/dialogmenu"
ctx = moderngl.create_standalone_context(backend="egl", require=330)
vertex = """#version 330
uniform sampler2D Sampler0;
uniform vec2 canvas;
in vec3 Position;
in vec4 Color;
in vec2 UV0;
out vec4 vertexColor;
out vec2 texCoord0;
flat out int dialogmenuMarked;
flat out vec4 dialogmenuImagePixels;
out vec2 dialogmenuImageCoord;
out vec3 dialogmenuSlice;
const mat4 ProjMat = mat4(1.0);
const mat4 ModelViewMat = mat4(1.0);
""" + (pack / "shaders/include/rgba_palette.glsl").read_text() + (shader / "vertex.glsl").read_text() + """
void main() {
    vec3 position = Position;
    vec2 uv = UV0;
    vertexColor = Color;
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0);
    dialogmenuImageCoord = vec2(0);
    dialogmenuSlice = vec3(0);
    dialogmenu_transform(position, uv);
    gl_Position = vec4(position.x / canvas.x * 2.0 - 1.0, 1.0 - position.y / canvas.y * 2.0, 0, 1);
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
""" + (shader / "fragment.glsl").read_text() + """
void main() {
    fragColor = texture(Sampler0, dialogmenu_uv()) * vertexColor;
    if (fragColor.a < 0.1) discard;
}
"""
program = ctx.program(vertex_shader=vertex, fragment_shader=fragment)
program["canvas"].value = 1014, 1024
glyphs = {}
for provider in json.loads((pack / "font/images_rgba.json").read_text())["providers"]:
    image = Image.open(pack / ("textures/" + provider["file"].split(":")[-1])).convert("RGBA")
    cw, ch = image.width // len(provider["chars"][0]), image.height // len(provider["chars"])
    for row, chars in enumerate(provider["chars"]):
        for col, char in enumerate(chars):
            glyphs[char] = (image, col * cw, row * ch, cw, ch)

def collect(value, cursor, quads, font="", payload=0):
    if isinstance(value, str):
        value = {"text": value}
    if isinstance(value, list):
        for child in value:
            collect(child, cursor, quads, font, payload)
        return
    font = value.get("font", font)
    payload = value.get("shadow_color", payload)
    for char in value.get("text", ""):
        if char == "\n":
            cursor[0] = 0
            cursor[1] += 1
        elif font == "dialogmenu:space":
            cursor[0] += ord(char) - 0xF0000 - 1024
        elif font == "dialogmenu:images_rgba":
            quads.append((char, cursor[0], cursor[1], payload))
            cursor[0] += 2
        else:
            raise AssertionError((font, char))
    for child in value.get("extra", []):
        collect(child, cursor, quads, font, payload)

checks = 0
for number, case in enumerate(json.loads((fixtures / "primitives.json").read_text())):
    quads = []
    collect(case["component"], [0, 0], quads)
    assert quads
    image = glyphs[quads[0][0]][0]
    texture = ctx.texture(image.size, 4, image.tobytes())
    texture.filter = moderngl.NEAREST, moderngl.NEAREST
    texture.use()
    vertices, indices = [], []
    for char, x, row, payload in quads:
        atlas, ax, ay, w, h = glyphs[char]
        assert atlas == image
        u0, v0 = (ax + .01) / image.width, (ay + .01) / image.height
        u1, v1 = (ax + w - .01) / image.width, (ay + h - .01) / image.height
        for shadow in (True, False):
            color = payload if shadow else 0xFFBEEF01
            rgba = [(color >> shift & 255) / 255 for shift in (16, 8, 0, 24)]
            left, top = x + int(shadow), row * 9 + 7 + int(shadow)
            base = len(vertices) // 9
            for px, py, u, v in ((left, top, u0, v0), (left, top+1, u0, v1),
                                 (left+1, top+1, u1, v1), (left+1, top, u1, v0)):
                vertices.extend((px, py, 0, *rgba, u, v))
            indices.extend(base+i for i in (0, 1, 2, 0, 2, 3))
    buffer = ctx.buffer(struct.pack(f"{len(vertices)}f", *vertices))
    index = ctx.buffer(struct.pack(f"{len(indices)}I", *indices))
    vao = ctx.vertex_array(program, [(buffer, "3f 4f 2f", "Position", "Color", "UV0")], index)
    for gui in (1, 2, 3):
        frame = ctx.simple_framebuffer((1014*gui, 1024*gui), components=4)
        frame.use()
        frame.clear()
        vao.render()
        actual = np.frombuffer(frame.read(components=4), np.uint8).reshape(1024*gui, 1014*gui, 4)[::-1]
        expected = np.zeros_like(actual)
        x, y, w, h, t = [case[key]*gui for key in ("x", "y", "width", "height", "thickness")]
        rgb = bytes.fromhex(case["color"][1:])
        expected[y:y+h, x:x+w] = [*rgb, 255]
        if case["type"] == "frame" and 2*t < min(w, h):
            expected[y+t:y+h-t, x+t:x+w-t] = 0
        if not np.array_equal(actual, expected):
            Image.fromarray(actual).save(output / "failed-actual.png")
            Image.fromarray(expected).save(output / "failed-expected.png")
            yy, xx = np.argwhere(np.any(actual != expected, axis=2))[0]
            raise AssertionError(f"case={number} {case['type']} GUI{gui} pixel={xx},{yy}: {actual[yy,xx]} != {expected[yy,xx]}")
        if gui == 1:
            Image.fromarray(actual).save(output / f"{number}-{case['type']}.png")
        checks += 1
        frame.release()
    vao.release()
    index.release()
    buffer.release()
    texture.release()
(output / "result.json").write_text(json.dumps({"checks": checks, "renderer": ctx.info["GL_RENDERER"]}, indent=2))
print(f"PASS primitive GPU pixels={checks}")
