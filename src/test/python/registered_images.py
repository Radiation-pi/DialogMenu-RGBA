"""Pixel checks of actual Java-exported registered sprites and shipped GLSL."""
import json
from pathlib import Path
import struct
import sys

import moderngl
import native_interface
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
const mat4 ProjMat = mat4(1./320.,0,0,0, 0,-1./320.,0,0, 0,0,1,0, -1,1,0,1);
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
program = ctx.program(vertex_shader=native_interface.vertex(vertex), fragment_shader=native_interface.fragment(fragment))
frame = ctx.simple_framebuffer((640, 640), components=4)
frame.use()
checks = 0


def draw(texture, payload, row, shadow, uv):
    rgba = [(payload >> shift & 255) / 255 for shift in (16, 8, 0, 24)]
    x = 21 if shadow else 20
    y = row * 9 + (0 if shadow else -1)
    u0, v0, u1, v1 = uv
    data = [0.0] * 27  # Native mixed-buffer draw beginning at vertex 3.
    for px, py, u, v in ((x, y, u0, v0), (x, y + 8, u0, v1),
                         (x + 8, y + 8, u1, v1), (x + 8, y, u1, v0)):
        data.extend((px, py, 0, *rgba, u, v))
    buffer = ctx.buffer(struct.pack(f"{len(data)}f", *data))
    indices = ctx.buffer(struct.pack("6I", 3, 4, 5, 3, 5, 6))
    vao = ctx.vertex_array(program, [(buffer, "3f 4f 2f", "Position", "Color", "UV0")], indices)
    texture.use()
    vao.render()
    vao.release()
    indices.release()
    buffer.release()


for source_size in (16, 256, "wide"):
    original = Image.open(fixtures / f"source-{source_size}.png").convert("RGBA")
    wrapped = Image.open(fixtures / f"wrapped-{source_size}.png").convert("RGBA")
    atlas = Image.new("RGBA", (1024, 1024), (244, 2, 177, 255))
    atlas.paste(wrapped, (128, 192))
    texture = ctx.texture(atlas.size, 4, atlas.tobytes())
    texture.filter = moderngl.NEAREST, moderngl.NEAREST
    uv = (128 / 1024, 192 / 1024, (128 + wrapped.width) / 1024, (192 + wrapped.height) / 1024)
    for width, height in ((255, 37), (256, 256), (257, 300), (300, 257), (512, 512)):
        target_height = max(1, int(min(height, width * original.height / original.width) + .5))
        target_width = max(1, int(target_height * original.width / original.height + .5))
        for top in (0, 8, 9, 17, 35):
            frame.clear()
            y = top
            while y < top + target_height:
                band = y // 9
                row = max(0, band - 1)
                payload = 0xA0000000 | (0 if band == 0 else 1 << 28)
                payload |= (target_height - 1) << 11 | ((top - row * 9) & 2047)
                draw(texture, payload, row, True, uv)
                draw(texture, 0xFFBEEF01, row, False, uv)
                y = min(top + target_height, (band + 1) * 9)
            actual = Image.frombytes("RGBA", (640, 640), frame.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
            expected = Image.new("RGBA", (640, 640))
            target = expected.load()
            source = original.load()
            for y in range(target_height):
                for x in range(round(target_width)):
                    sx = int((x + .5) * original.width / target_width - .0001)
                    sy = int((y + .5) * original.height / target_height - .0001)
                    r, g, b, a = source[sx, sy]
                    target[20 + x, top + y] = (round(r / 3), round(g * 2 / 3), b, a)
            if actual.tobytes() != expected.tobytes():
                actual.save(output / "failed-actual.png")
                expected.save(output / "failed-expected.png")
                for index, (a, b) in enumerate(zip(actual.getdata(), expected.getdata())):
                    if a != b:
                        raise AssertionError(f"source={source_size}, box={width}x{height}, display={target_width}x{target_height}, top={top}, "
                                             f"pixel={index % 640},{index // 640}: {a} != {b}")
            checks += 1
    texture.release()
(output / "result.json").write_text(json.dumps({
    "checks": checks, "renderer": ctx.info["GL_RENDERER"],
    "scope": "Java-generated full source sprites, display255/256/257/300/512, row seams, tint, original alpha; offline EGL only"
}, indent=2) + "\n")
print(f"PASS registered-image GPU checks={checks}")
