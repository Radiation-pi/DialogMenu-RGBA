from io import BytesIO
import json
import unittest

from PIL import Image

from corner_texture import wrap


class CornerTextureTests(unittest.TestCase):
    def check_copy(self, size, animation):
        original = Image.new("RGBA", size)
        for y in range(size[1]):
            for x in range(size[0]):
                original.putpixel((x, y), (x % 256, y % 256, (x + y) % 256, (x * y) % 256))
        buffer = BytesIO()
        original.save(buffer, format="PNG")
        meta = {"texture": {"blur": False}, "custom": {"untouched": 1}}
        if animation is not None:
            meta["animation"] = animation
        raw_meta = json.dumps(meta).encode()
        key, tag, png, encoded, dimensions = wrap(buffer.getvalue(), raw_meta, "vanilla")
        self.assertNotEqual(key, wrap(buffer.getvalue(), raw_meta, "ce")[0])
        result_meta = json.loads(encoded)
        self.assertEqual(result_meta["texture"], meta["texture"])
        self.assertEqual(result_meta["custom"], meta["custom"])
        if animation is None:
            width, height = size
            self.assertNotIn("animation", result_meta)
        else:
            if "width" not in animation and "height" not in animation:
                width = height = min(size)
            else:
                width, height = animation.get("width", size[0]), animation.get("height", size[1])
        fw, fh = dimensions[2:]
        self.assertEqual(dimensions[:2], (width, height))
        self.assertEqual(fw & (fw - 1), 0)
        self.assertEqual(fh & (fh - 1), 0)
        self.assertGreaterEqual(fw, width + 4)
        self.assertGreaterEqual(fh, height + 4)
        if animation is not None:
            self.assertEqual(result_meta["animation"], {**animation, "width": fw, "height": fh})
        columns, rows = size[0] // width, size[1] // height
        with Image.open(BytesIO(png)) as wrapped:
            self.assertEqual(wrapped.size, (columns * fw, rows * fh))
            for row in range(rows):
                for column in range(columns):
                    x, y = column * fw, row * fh
                    self.assertEqual(
                        wrapped.crop((x + 2, y + 2, x + 2 + width, y + 2 + height)).tobytes(),
                        original.crop((column * width, row * height,
                                       (column + 1) * width, (row + 1) * height)).tobytes())
                    for corner, (cx, cy) in enumerate(
                            ((0, 0), (0, fh - 1), (fw - 1, fh - 1), (fw - 1, 0))):
                        self.assertEqual(wrapped.getpixel((x + cx, y + cy)), (*tag, 252 + corner))
                    self.assertEqual(wrapped.getpixel((x + 1, y + 2)),
                                     original.getpixel((column * width, row * height)))

    def test_static_rectangles_and_alpha(self):
        for size in ((1, 1), (7, 13), (16, 16), (64, 32)):
            with self.subTest(size=size):
                self.check_copy(size, None)

    def test_implicit_vertical_and_horizontal_animations(self):
        for size in ((16, 64), (64, 16)):
            with self.subTest(size=size):
                self.check_copy(size, {"frametime": 3, "interpolate": True,
                                       "frames": [3, {"index": 1, "time": 7}, 0, 3]})

    def test_explicit_rectangular_grid_and_single_dimension(self):
        for size, animation in (
                ((24, 32), {"width": 8, "height": 16, "frames": [5, 0, 2]}),
                ((24, 16), {"width": 8, "frametime": 2}),
                ((16, 24), {"height": 8, "interpolate": True})):
            with self.subTest(size=size, animation=animation):
                self.check_copy(size, animation)


if __name__ == "__main__":
    unittest.main(verbosity=2)
