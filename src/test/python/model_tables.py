"""Read every model-table value through the production GLSL using offline EGL."""
from array import array
import json
from pathlib import Path
import sys
import time
from zipfile import ZipFile

import moderngl

root, output = map(Path, sys.argv[1:3])
output.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(root / "tools/models"))
from prepare import LIMIT, allocate, table

with ZipFile(root / "src/main/resources/vanilla-inventory.zip") as archive:
    bundled, _ = allocate(json.loads(archive.read("state.json"))["entries"], [], 0)

ctx = moderngl.create_standalone_context(backend="egl", require=330)
ctx.simple_framebuffer((1, 1)).use()
checks = 0
for kind in ("vanilla", "ce"):
    for count in ("bundled", 0, 1, 51, 52, 773, LIMIT):
        records = bundled if count == "bundled" else [
            None if i % 17 == 0 else [(i * 20 + j - 32768) / 16 for j in range(20)]
            for i in range(count)]
        source = table(records, kind).decode()
        values = [float(f"{value:.6f}") for record in records or [None]
                  for value in record or [0.0] * 20]
        expected = array("f", values)
        start = time.monotonic()
        program = ctx.program(vertex_shader="#version 330\n" + source + f"""
out vec4 value;
void main() {{
    value = model{kind.title()}Data(gl_VertexID);
    gl_Position = vec4(0.0);
}}
""", varyings=["value"])
        vao = ctx.vertex_array(program, [])
        buffer = ctx.buffer(reserve=len(expected) * expected.itemsize)
        vao.transform(buffer, mode=moderngl.POINTS, vertices=len(expected) // 4)
        actual = array("f")
        actual.frombytes(buffer.read())
        if actual != expected:
            mismatches = [(i, a, b) for i, (a, b) in enumerate(zip(actual, expected)) if a != b]
            raise AssertionError(f"{kind} count={count}: {len(mismatches)} mismatches; first={mismatches[:8]}")
        checks += len(expected)
        print(f"PASS {kind} records={count} values={len(expected)} seconds={time.monotonic()-start:.3f}",
              flush=True)
        buffer.release()
        vao.release()
        program.release()

(output / "result.json").write_text(json.dumps({
    "passed": checks, "failed": 0,
    "renderer": ctx.info["GL_RENDERER"],
    "scope": "All table values through EGL, including holes and the full model address space; not NVIDIA acceptance",
}, indent=2) + "\n")
print(f"PASS model table GPU values={checks}")
