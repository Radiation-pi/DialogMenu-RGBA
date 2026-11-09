"""Render Java-exported fixed glyphs through shipped GLSL, without a game client."""
import json
import math
from pathlib import Path
import struct
import sys

import moderngl
from PIL import Image

shader, fixtures, output = map(Path, sys.argv[1:4])
output.mkdir(parents=True, exist_ok=True)
ctx = moderngl.create_standalone_context(backend="egl", require=330)
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
vec3 dialogmenu_color(int index) { return vec3(85.0,170.0,255.0)/255.0; }
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
    gl_Position = vec4(position.x / 320.0 - 1.0, 1.0 - position.y / 320.0, 0, 1);
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
frame = ctx.simple_framebuffer((640, 640), components=4)
frame.use()
wrapped = Image.open(fixtures / "glyph.png").convert("RGBA")
sw, sh, _, _ = wrapped.getpixel((1, 0))
atlas = Image.new("RGBA", (256, 256), (211, 31, 91, 255))
atlas.paste(wrapped, (32, 48))
texture = ctx.texture(atlas.size, 4, atlas.tobytes())
texture.filter = moderngl.NEAREST, moderngl.NEAREST
uv = ((32 + .01) / 256, (48 + .01) / 256,
      (32 + wrapped.width - .01) / 256, (48 + wrapped.height - .01) / 256)


def draw(payload, row, shadow, bold_offset):
    rgba = [(payload >> shift & 255) / 255 for shift in (16, 8, 0, 24)]
    x, y = 64 + int(shadow) + bold_offset, row * 9 + 7 + int(shadow)
    u0, v0, u1, v1 = uv
    data = [0.0] * 27
    for px, py, u, v in ((x, y, u0, v0), (x, y + 1, u0, v1),
                         (x + 1, y + 1, u1, v1), (x + 1, y, u1, v0)):
        data.extend((px, py, 0, *rgba, u, v))
    buffer = ctx.buffer(struct.pack(f"{len(data)}f", *data))
    indices = ctx.buffer(struct.pack("6I", 3, 4, 5, 3, 5, 6))
    vao = ctx.vertex_array(program, [(buffer, "3f 4f 2f", "Position", "Color", "UV0")], indices)
    texture.use()
    vao.render()
    vao.release()
    indices.release()
    buffer.release()


checks = 0
for case in json.loads((fixtures / "cases.json").read_text()):
    width, height, top, italic = (case[key] for key in ("width", "height", "top", "italic"))
    for bold in (False, True):
        frame.clear()
        for row in case["rows"]:
            for shift in range(2 if bold else 1):
                draw(row["payload"], row["row"], True, shift)
                draw(0xFFBEEF01, row["row"], False, shift)
        actual = Image.frombytes("RGBA", frame.size, frame.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        expected = Image.new("RGBA", frame.size)
        pixels = expected.load()
        for y in range(height):
            shear = 1 - (y + .5) / 4 if italic else 0
            sy = min(sh - 1, math.floor((y + .5) * sh / height - .0001))
            for shift in range(2 if bold else 1):
                for x in range(math.floor(64 + shear + shift), math.ceil(64 + shear + shift + width)):
                    local = x + .5 - (64 + shear + shift)
                    if not 0 <= local < width:
                        continue
                    sx = min(sw - 1, math.floor((math.floor(local) + .5) * sw / width - .0001))
                    alpha = wrapped.getpixel((1 + max(0, sx), 1 + sy))[3]
                    if alpha >= 26:
                        pixels[x, top + y] = (85, 170, 255, alpha)
        if actual.tobytes() != expected.tobytes():
            actual.save(output / "failed-actual.png")
            expected.save(output / "failed-expected.png")
            for index, (a, b) in enumerate(zip(actual.getdata(), expected.getdata())):
                if a != b:
                    raise AssertionError(f"{case}, bold={bold}, pixel={index % 640},{index // 640}: {a} != {b}")
        checks += 1
        if width == 257 and height == 128 and top == 17 and italic and bold:
            actual.save(output / "stretched-italic-bold.png")
(output / "result.json").write_text(json.dumps({
    "checks": checks, "renderer": ctx.info["GL_RENDERER"],
    "scope": "Fixed Java glyph source, width1..512/height1..128, row seams, italic, bold, tint, hidden pass; EGL only"
}, indent=2) + "\n")
print(f"PASS unified-text GPU checks={checks}")
