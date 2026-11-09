"""Menu-only sprite copies; source pixels and native animation order are preserved."""
from copy import deepcopy
from functools import lru_cache
from io import BytesIO
import hashlib
import json

from PIL import Image

from model_catalog import Unsupported

PADDING = 2


@lru_cache(maxsize=128)
def wrap(png, metadata, kind):
    key = hashlib.sha256(kind.encode() + b"\0" + png + b"\0" + (metadata or b"")).hexdigest()
    tag = tuple(bytes.fromhex(key[:6]))
    meta = json.loads(metadata) if metadata else {}
    animation = meta.get("animation")
    with Image.open(BytesIO(png)) as source:
        source = source.convert("RGBA")
    width, height = source.size
    if animation is not None:
        if "width" not in animation and "height" not in animation:
            width = height = min(source.size)
        else:
            width = animation.get("width", width)
            height = animation.get("height", height)
    if (not isinstance(width, int) or not isinstance(height, int) or width < 1 or height < 1
            or source.width % width or source.height % height):
        raise Unsupported("Invalid model animation frame dimensions")
    columns, rows = source.width // width, source.height // height
    # Do not lower the mip level of the shared native atlas with a 16 -> 20 sprite.
    fw = 1 << (width + 2 * PADDING - 1).bit_length()
    fh = 1 << (height + 2 * PADDING - 1).bit_length()
    target = Image.new("RGBA", (columns * fw, rows * fh))
    for row in range(rows):
        for column in range(columns):
            frame = source.crop((column * width, row * height,
                                 (column + 1) * width, (row + 1) * height))
            tile = Image.new("RGBA", (fw, fh))
            # Extrude edges so filtering at the original UV boundary cannot see the markers.
            tile.paste(frame, (PADDING, PADDING))
            for x in range(PADDING):
                tile.paste(frame.crop((0, 0, 1, height)), (x, PADDING))
            for x in range(PADDING + width, fw):
                tile.paste(frame.crop((width - 1, 0, width, height)), (x, PADDING))
            for y in range(PADDING):
                tile.paste(tile.crop((0, PADDING, fw, PADDING + 1)), (0, y))
            for y in range(PADDING + height, fh):
                tile.paste(tile.crop((0, PADDING + height - 1, fw, PADDING + height)), (0, y))
            for corner, point in enumerate(((0, 0), (0, fh - 1), (fw - 1, fh - 1), (fw - 1, 0))):
                tile.putpixel(point, (*tag, 252 + corner))
            target.paste(tile, (column * fw, row * fh))
    result = BytesIO()
    target.save(result, format="PNG")
    if animation is not None:
        meta = deepcopy(meta)
        meta["animation"]["width"] = fw
        meta["animation"]["height"] = fh
    return key, tag, result.getvalue(), (
        json.dumps(meta, separators=(",", ":")).encode() if metadata else None), (width, height, fw, fh)
