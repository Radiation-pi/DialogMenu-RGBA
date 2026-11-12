package dev.dialogmenu;

/** 字体几何同时用于可见字形、换行与原生命中。 */
record TextStyle(int size, double scale) {
    TextStyle {
        if (size < 1 || size > 128 || !Double.isFinite(scale) || scale <= 0
            || Math.round(size * scale) < 1 || Math.round(size * scale) > 128)
            throw new IllegalArgumentException("等比缩放后文字尺寸须在支持范围内，scale须为有限正数");
    }

    int height() { return (int) Math.round(size * scale); }
    int space() { return (int) Math.round(size * scale / 2); }
    int stride() { return height() + Math.max(1, (int) Math.round(scale)); }
}
