"""Check emitted calibration boundaries through shipped GLSL, without a game client."""
import json
from pathlib import Path
import sys

root, fixtures = map(Path, sys.argv[1:3])
source = root / "src/test/python/unified_text.py"
script = source.read_text().split("\nchecks = 0\n", 1)[0]
script = script.replace(
    "vec3 dialogmenu_color(int index) { return vec3(85.0,170.0,255.0)/255.0; }",
    "vec3 dialogmenu_color(int index) { return vec3(1); }",
).replace('fixtures / "glyph.png"', 'fixtures / "rule-source.png"')
out = fixtures / "boundary-gpu"
sys.argv = [str(source), str(root / "src/main/resources/rgba"), str(fixtures), str(out)]
exec(compile(script, str(source), "exec"))
cases = json.loads((fixtures / "boundary-cases.json").read_text())
checks = 0
for gui in (2, 3, 4):
    frame.release()
    frame = ctx.simple_framebuffer((640 * gui, 640 * gui), components=4)
    frame.use()
    for case in cases:
        frame.clear()
        for row in case["rows"]:
            for offset in range(case["repeat"]):
                draw(row["payload"], row["row"], True, offset)
                draw(0xFFBEEF01, row["row"], False, offset)
        actual = Image.frombytes("RGBA", frame.size, frame.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        # Contract: one solid white 320x1 line whose bottom is round(205 * scale).
        expected = Image.new("RGBA", frame.size)
        top = int(case["scale"] * 205 + .5) - 1
        expected.paste((255, 255, 255, 255), (64 * gui, top * gui, 384 * gui, (top + 1) * gui))
        if actual.tobytes() != expected.tobytes():
            actual.save(out / "failed-actual.png")
            expected.save(out / "failed-expected.png")
            raise AssertionError(f"Boundary mismatch GUI{gui}, scale={case['scale']}")
        if case["scale"] in (.8, 1, 2.2):
            actual.save(out / f"boundary-{case['scale']}-gui{gui}.png")
        checks += 1
result = {"checks": checks, "failures": 0, "renderer": ctx.info["GL_RENDERER"],
          "scope": "Actual emitted boundary payloads at GUI2/3/4; offline EGL, not a client"}
(out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
