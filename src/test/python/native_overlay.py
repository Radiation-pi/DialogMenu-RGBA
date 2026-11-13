"""Render production-encoded native-bar shapes over simulated native rectangles using production GLSL."""
import json
from pathlib import Path
import struct
import sys

import moderngl
import numpy as np
from PIL import Image
import native_interface

shader, fixtures, output = map(Path, sys.argv[1:4])
output.mkdir(parents=True, exist_ok=True)
pack = fixtures / "pack/assets/dialogmenu"
profiles = (pack / "shaders/include/native_profiles.glsl").read_text()
ctx = moderngl.create_standalone_context(backend="egl", require=330)
vertex = """#version 330
uniform sampler2D Sampler0;
in vec3 Position; in vec4 Color; in vec2 UV0;
out vec4 vertexColor; out vec2 texCoord0;
flat out int dialogmenuMarked; flat out vec4 dialogmenuImagePixels;
out vec2 dialogmenuImageCoord; out vec3 dialogmenuSlice;
uniform mat4 ProjMat;
const mat4 ModelViewMat = mat4(1);
""" + (pack / "shaders/include/rgba_palette.glsl").read_text() + (shader / "vertex.glsl").read_text() + """
void main() {
    vec3 position = Position; vec2 uv = UV0; vertexColor = Color;
    dialogmenuMarked = 0; dialogmenuImagePixels = vec4(0);
    dialogmenuImageCoord = vec2(0); dialogmenuSlice = vec3(0);
    dialogmenu_transform(position, uv); dialogmenu_finish_native(position);
    gl_Position = ProjMat * vec4(position, 1); texCoord0 = uv;
}
"""
fragment = """#version 330
uniform sampler2D Sampler0;
in vec4 vertexColor; in vec2 texCoord0;
flat in int dialogmenuMarked; flat in vec4 dialogmenuImagePixels;
in vec2 dialogmenuImageCoord; in vec3 dialogmenuSlice;
out vec4 fragColor;
""" + (shader / "fragment.glsl").read_text() + """
void main() { fragColor = texture(Sampler0, dialogmenu_uv()) * vertexColor; if (fragColor.a < .1) discard; }
"""
program = ctx.program(vertex_shader=native_interface.vertex(vertex, profiles),
                      fragment_shader=native_interface.fragment(fragment, profiles))
advances, glyphs = {}, {}
for font in ("space", "images_rgba", "rgba_text", "hits"):
    file = pack / f"font/{font}.json"
    if not file.exists():
        continue
    for provider in json.loads(file.read_text())["providers"]:
        if provider["type"] == "space":
            advances.update({(f"dialogmenu:{font}", ch): value for ch, value in provider["advances"].items()})
        elif provider["type"] == "bitmap":
            image = Image.open(pack / ("textures/" + provider["file"].split(":")[-1])).convert("RGBA")
            cw, ch = image.width // len(provider["chars"][0]), image.height // len(provider["chars"])
            for row, chars in enumerate(provider["chars"]):
                for col, char in enumerate(chars):
                    if char == "\x00":
                        continue
                    box = (col*cw, row*ch, (col+1)*cw, (row+1)*ch)
                    bounds = image.crop(box).getbbox()
                    advance = int(.5 + (bounds[2] if bounds else 0) * provider["height"] / ch) + 1
                    advances[(f"dialogmenu:{font}", char)] = advance
                    if font == "images_rgba":
                        glyphs[char] = image, col*cw, row*ch, cw, ch


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
            cursor[0] = cursor[2]
            cursor[1] += 9
            continue
        if font == "dialogmenu:images_rgba":
            quads.append((char, cursor[0], cursor[1], payload))
        cursor[0] += advances.get((font, char), 2)
    for child in value.get("extra", []):
        collect(child, cursor, quads, font, payload)


def draw(component, y, frame, x=14):
    quads = []
    cursor = [x, y, x]
    collect(component, cursor, quads)
    for char, x, y, payload in quads:
        image, ax, ay, w, h = glyphs[char]
        texture = ctx.texture(image.size, 4, image.tobytes())
        texture.filter = moderngl.NEAREST, moderngl.NEAREST
        texture.use()
        vertices, indices = [], []
        for shadow in (True, False):
            color = payload if shadow else 0xFFBEEF01
            rgba = [(color >> shift & 255) / 255 for shift in (16, 8, 0, 24)]
            left, top = x + int(shadow), y + 7 + int(shadow)
            base = len(vertices) // 9
            for px, py, u, v in ((left, top, ax+.01, ay+.01),
                                 (left, top+1, ax+.01, ay+h-.01),
                                 (left+1, top+1, ax+w-.01, ay+h-.01),
                                 (left+1, top, ax+w-.01, ay+.01)):
                vertices.extend((px, py, 0, *rgba, u/image.width, v/image.height))
            indices.extend(base+i for i in (0, 1, 2, 0, 2, 3))
        buffer = ctx.buffer(struct.pack(f"{len(vertices)}f", *vertices))
        index = ctx.buffer(struct.pack(f"{len(indices)}I", *indices))
        vao = ctx.vertex_array(program, [(buffer, "3f 4f 2f", "Position", "Color", "UV0")], index)
        vao.render()
        vao.release()
        index.release()
        buffer.release()
        texture.release()


checks = 0
projection = np.array(((1/240, 0, 0, 0), (0, -2/207, 0, 0),
                       (0, 0, 1, 0), (-1, 1, 0, 1)), np.float32)
program["ProjMat"].write(projection.tobytes())
for case in json.loads((fixtures / "fixtures.json").read_text()):
    for gui in (1, 2, 3):
        frame = ctx.simple_framebuffer((480*gui, 207*gui), components=4)
        frame.use()
        frame.clear()
        ctx.scissor = (0, (207-case["bodyRows"]*9)*gui, 480*gui, case["bodyRows"]*9*gui)
        draw(case["body"], 0, frame)
        ctx.scissor = None
        # Actual widget drawing order is tested separately by the real Minecraft client.
        for y, h in ((77, 20), (107, 40), (157, 20), (187, 20)):
            frame.clear(1, .5, 0, 1, viewport=(101*gui, (207-y-h)*gui, 280*gui, h*gui))
        draw(case["foreground"], case["bodyRows"]*9+case["carrier"], frame)
        actual = np.frombuffer(frame.read(components=4), np.uint8).reshape(207*gui, 480*gui, 4)[::-1]
        expected = np.zeros_like(actual)
        mode = case["id"]
        spans = []
        if mode in ("native-overlay", "native-none"):
            spans = [(77, 130)]
        else:
            for y, h in ((77, 20), (107, 40), (157, 20), (187, 20)):
                if mode == "native-bottom":
                    spans.append((y+h//2, h//2))
                elif mode == "native-top":
                    spans.append((y, h//2))
                else:
                    middle = 14 if h == 40 else 6
                    spans.append((y+(h-middle)//2, middle))
        for y, h in spans:
            expected[y*gui:(y+h)*gui, 80*gui:400*gui] = [34, 63, 85, 255]
        for i, (y, h) in enumerate(((77, 20), (107, 40), (157, 20), (187, 20))):
            if i < 3 or mode == "native-none":
                expected[y*gui:(y+h)*gui, 101*gui:381*gui] = [255, 128, 0, 255]
            else:
                region = expected[y*gui:(y+h)*gui, 101*gui:381*gui]
                region[region[:, :, 3] == 0] = [255, 128, 0, 255]
        if not np.array_equal(actual, expected):
            Image.fromarray(actual).save(output / "failed-actual.png")
            Image.fromarray(expected).save(output / "failed-expected.png")
            yy, xx = np.argwhere(np.any(actual != expected, axis=2))[0]
            raise AssertionError(f"{mode} GUI{gui} {xx},{yy}: {actual[yy,xx]} != {expected[yy,xx]}")
        if gui == 1:
            Image.fromarray(actual).save(output / f"{mode}.png")
        checks += 1
        frame.release()

for case in json.loads((fixtures / "scaled-fixtures.json").read_text()):
    for screen in (480, 1200):
        height = max(case["canvasHeight"], case["bodyRows"]*9 + 144)
        content = case["contentWidth"]
        left = max(0, screen-content-20)//2 + 10
        canvas_x = left + (content-case["canvasWidth"]-10)//2 + 4
        projection[0, 0], projection[1, 1] = 2/screen, -2/height
        program["ProjMat"].write(projection.tobytes())
        for gui in (1, 2, 3):
            frame = ctx.simple_framebuffer((screen*gui, height*gui), components=4)
            frame.use()
            frame.clear()
            ctx.scissor = (0, (height-case["bodyRows"]*9)*gui, screen*gui, case["bodyRows"]*9*gui)
            draw(case["body"], 0, frame, canvas_x)
            ctx.scissor = None
            expected = np.zeros((height*gui, screen*gui, 4), np.uint8)

            def fill(rect, color):
                x, y, w, h = rect
                x0, y0, x1, y1 = max(0, x), max(0, y), min(screen, x+w), min(height, y+h)
                if x1 > x0 and y1 > y0:
                    expected[y0*gui:y1*gui, x0*gui:x1*gui] = color

            for rect in case["rectangles"]:
                fill((canvas_x+rect["x"], rect["y"], rect["width"], rect["height"]), (34, 63, 85, 255))
            for control in case["controls"]:
                x, y = left+(content-control["width"])//2, case["bodyRows"]*9+control["y"]
                w, h = control["width"], control["height"]
                x0, x1 = max(0, x), min(screen, x+w)
                if x1 > x0:
                    frame.clear(1, .5, 0, 1, viewport=(x0*gui, (height-y-h)*gui, (x1-x0)*gui, h*gui))
                fill((x, y, w, h), (255, 128, 0, 255))
                if control["button"]:
                    for rect in case["rectangles"]:
                        if rect["z"] <= control["z"]:
                            continue
                        x0, y0 = max(x, canvas_x+rect["x"]), max(y, rect["y"])
                        x1, y1 = min(x+w, canvas_x+rect["x"]+rect["width"]), min(y+h, rect["y"]+rect["height"])
                        fill((x0, y0, max(0, x1-x0), max(0, y1-y0)), (34, 63, 85, 255))
            draw(case["foreground"], case["bodyRows"]*9+case["carrier"], frame, canvas_x)
            actual = np.frombuffer(frame.read(components=4), np.uint8).reshape(height*gui, screen*gui, 4)[::-1]
            if not np.array_equal(actual, expected):
                prefix = f'{case["id"]}-{case["scale"]}-{screen}-gui{gui}'
                Image.fromarray(actual).save(output / (prefix+"-actual.png"))
                Image.fromarray(expected).save(output / (prefix+"-expected.png"))
                yy, xx = np.argwhere(np.any(actual != expected, axis=2))[0]
                raise AssertionError(f"{prefix} {xx},{yy}: {actual[yy,xx]} != {expected[yy,xx]}")
            if gui == 1 and case["id"] == "native-overlay":
                Image.fromarray(actual).save(output / f'scale-{case["scale"]}-{screen}.png')
            checks += 1
            frame.release()
(output / "results.json").write_text(json.dumps({"checks": checks, "renderer": ctx.info["GL_RENDERER"]}, indent=2))
print(f"PASS native overlay GPU pixels={checks}")
