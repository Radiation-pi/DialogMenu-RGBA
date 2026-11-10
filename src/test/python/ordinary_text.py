"""Ordinary GUI colors must never activate menu transforms (offline EGL)."""
from array import array
import json
from pathlib import Path
import sys

import moderngl

pack, output = map(Path, sys.argv[1:3])
output.mkdir(parents=True, exist_ok=True)
ctx = moderngl.create_standalone_context(backend="egl", require=430)
ctx.simple_framebuffer((1, 1)).use()
include = pack / "assets/dialogmenu/shaders/include"
model = (include / "model_vertex.glsl").read_text().replace("gl_VertexIndex", "gl_VertexID")
tables = "".join((pack / f"assets/{ns}/shaders/include/models.glsl").read_text()
                 for ns in ("dialogmenu_vanilla", "dialogmenu_ce"))
source = """#version 430
#extension GL_ARB_separate_shader_objects : require
#define IS_GUI
#define gl_BaseVertex 0
uniform sampler2D Sampler0;
uniform int firstRgb;
uniform int alphaSweep;
uniform vec3 testPosition;
uniform mat4 ProjMat;
const mat4 ModelViewMat = mat4(1.0);
vec4 Color;
vec2 UV0 = vec2(0.25);
vec4 vertexColor;
int dialogmenuMarked;
vec4 dialogmenuImagePixels;
vec2 dialogmenuImageCoord;
vec3 dialogmenuSlice;
out float unchanged;
""" + (include / "rgba_palette.glsl").read_text() + (include / "rgba_vertex.glsl").read_text() + tables + model + """
void main() {
    int rgb = alphaSweep != 0 ? firstRgb : firstRgb + gl_VertexID;
    int alpha = alphaSweep != 0 ? gl_VertexID : 255;
    Color = vec4(float((rgb >> 16) & 255), float((rgb >> 8) & 255),
                 float(rgb & 255), float(alpha)) / 255.0;
    vec3 position = testPosition;
    vec2 uv = UV0;
    vertexColor = Color;
    dialogmenuMarked = 0;
    dialogmenuSlice = vec3(0.0);
    dialogmenu_transform(position, uv);
    dialogmenu_model(position, uv);
    unchanged = all(equal(vertexColor, Color)) && all(equal(position, testPosition))
        && all(equal(uv, UV0)) && dialogmenuMarked == 0 && dialogmenuModelVisible == 1.0 ? 1.0 : 0.0;
    gl_Position = vec4(position, 1.0);
}
"""
program = ctx.program(vertex_shader=source, varyings=["unchanged"])
vao = ctx.vertex_array(program, [])
buffer = ctx.buffer(reserve=65536 * 4)
texture = ctx.texture((2, 2), 4, bytes([255] * 16))
texture.use()
checks = 0


def verify(rgb, count, sweep):
    global checks
    program["firstRgb"].value = rgb
    program["alphaSweep"].value = int(sweep)
    vao.transform(buffer, mode=moderngl.POINTS, vertices=count)
    values = array("f", buffer.read(size=count * 4))
    for i, value in enumerate(values):
        if value != 1:
            raise AssertionError(f"Ordinary text intercepted: RGB=#{rgb if sweep else rgb+i:06X} "
                                 f"A={i if sweep else 255}, position={program['testPosition'].value}")
    checks += count


colors = (0, 0xFFFFFF, 0xBEEF01, 0x174783, 0x1D5395, 0x1D5495, 0x1D5595,
          0x2878C8, 0x55FFFF, 0xFF5555, 0x123456, 0xFF00FF, 0x008000, 0x00FF00)
for center in (160, 960, 1920, 3840):
    program["ProjMat"].write(array("f", [1/center, 0, 0, 0, 0, -1/center, 0, 0,
                                        0, 0, 1, 0, -1, 1, 0, 1]).tobytes())
    for x in (-4096, 0, 20, center, 2 * center):
        program["testPosition"].value = (x, 24, 0)
        for color in colors:
            verify(color, 256, True)
# Exhaust all ordinary RGB colors, not just the previously failing marker.
program["testPosition"].value = (20, 24, 0)
for high in range(256):
    verify(high << 16, 65536, False)
(output / "result.json").write_text(json.dumps({
    "checks": checks, "failed": 0, "renderer": ctx.info["GL_RENDERER"],
    "scope": "All RGB24 at A255; collision colors across all alpha bytes and wide GUI positions; no client",
}, indent=2))
print(f"PASS ordinary text isolation checks={checks}")
