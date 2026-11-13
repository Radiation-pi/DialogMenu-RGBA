"""Replay Java-exported image components through production GLSL, including row seams."""
import json
from pathlib import Path
import struct
import sys

import moderngl
import native_interface
import numpy as np
from PIL import Image

shader, fixtures, output = map(Path, sys.argv[1:4])
output.mkdir(parents=True, exist_ok=True)
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
vec3 dialogmenu_color(int index) { return vec3(1.0); }
const mat4 ProjMat = mat4(1.0);
const mat4 ModelViewMat = mat4(1.0);
""" + (shader / "vertex.glsl").read_text() + """
void main() {
    vec3 position = Position;
    vec2 uv = UV0;
    vertexColor = Color;
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0);
    dialogmenuImageCoord = vec2(0);
    dialogmenuSlice = vec3(0);
    dialogmenu_transform(position, uv);
    gl_Position = vec4(position.x / canvas.x * 2.0 - 1.0,
                       1.0 - position.y / canvas.y * 2.0, 0, 1);
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
program = ctx.program(vertex_shader=native_interface.vertex(vertex), fragment_shader=native_interface.fragment(fragment))
checks = 0
for number, case in enumerate(json.loads((fixtures / "fixtures.json").read_text())):
    source = np.array(Image.open(fixtures / "images" / case["source"]).convert("RGBA"))
    cw, ch = case.get("canvasWidth", 1014), case.get("canvasHeight", 1024)
    program["canvas"].value = cw, ch
    sprite = case["quads"][0]["sprite"]
    namespace, path = sprite.split(":")
    wrapped = Image.open(fixtures / f"pack/assets/{namespace}/textures/{path}.png").convert("RGBA")
    atlas = Image.new("RGBA", (1024, 1024), (244, 2, 177, 255))
    atlas.paste(wrapped, (128, 192))
    texture = ctx.texture(atlas.size, 4, atlas.tobytes())
    texture.filter = moderngl.NEAREST, moderngl.NEAREST
    texture.use()
    u0, v0 = 128 / 1024, 192 / 1024
    u1, v1 = (128 + wrapped.width) / 1024, (192 + wrapped.height) / 1024
    vertices = []
    indices = []
    for quad in case["quads"]:
        for shadow in (True, False):
            payload = quad["payload"] if shadow else 0xFFBEEF01
            rgba = [(payload >> shift & 255) / 255 for shift in (16, 8, 0, 24)]
            x = quad["x"] + int(shadow)
            y = quad["row"] * 9 - int(not shadow)
            base = len(vertices) // 9
            for px, py, u, v in ((x, y, u0, v0), (x, y + 8, u0, v1),
                                 (x + 8, y + 8, u1, v1), (x + 8, y, u1, v0)):
                vertices.extend((px, py, 0, *rgba, u, v))
            indices.extend(base + i for i in (0, 1, 2, 0, 2, 3))
    buffer = ctx.buffer(struct.pack(f"{len(vertices)}f", *vertices))
    index = ctx.buffer(struct.pack(f"{len(indices)}I", *indices))
    vao = ctx.vertex_array(program, [(buffer, "3f 4f 2f", "Position", "Color", "UV0")], index)
    for gui in (1, 2, 3):
        frame = ctx.simple_framebuffer((cw * gui, ch * gui), components=4)
        frame.use()
        frame.clear()
        vao.render()
        actual = np.frombuffer(frame.read(components=4), np.uint8).reshape(ch * gui, cw * gui, 4)[::-1]
        expected = np.zeros_like(actual)
        w, h = case["width"], case["height"]
        sx = np.floor((np.arange(w * gui) + .5) * source.shape[1] / (w * gui) - .0001).astype(int)
        sy = np.floor((np.arange(h * gui) + .5) * source.shape[0] / (h * gui) - .0001).astype(int)
        x, y = case["x"] * gui, case["y"] * gui
        expected[y:y + h * gui, x:x + w * gui] = source[sy[:, None], sx]
        if not np.array_equal(actual, expected):
            Image.fromarray(actual).save(output / "failed-actual.png")
            Image.fromarray(expected).save(output / "failed-expected.png")
            yy, xx = np.argwhere(np.any(actual != expected, axis=2))[0]
            raise AssertionError(f"case={number} source={case['source']} box={case['boxWidth']}x{case['boxHeight']}"
                                 f" gui={gui} pixel={xx},{yy} {actual[yy,xx]} != {expected[yy,xx]}")
        if gui == 1 and (w > 512 or h > 512):
            Image.fromarray(actual).save(output / f"source-{case['source']}-{w}x{h}.png")
        checks += 1
        frame.release()
    vao.release()
    index.release()
    buffer.release()
    texture.release()
(output / "result.json").write_text(json.dumps({
    "checks": checks, "renderer": ctx.info["GL_RENDERER"],
    "scope": "Actual Java components and resources, whole-image GPU pixels, GUI1/2/3; not client acceptance"
}, indent=2) + "\n")
print(f"PASS proportional image GPU checks={checks}")
