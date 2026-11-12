vec2 dialogmenu_uv() {
    vec2 uv = texCoord0;
    if (dialogmenuMarked == 2) {
        vec2 atlas = vec2(textureSize(Sampler0, 0));
        // Sample the full source at screen fragments, not an intermediate logical-pixel grid.
        vec2 sourcePixel = dialogmenuImageCoord * dialogmenuImagePixels.zw * atlas;
        // 恰好落在采样边界时取前一个纹素，再采样其中心。
        uv = dialogmenuImagePixels.xy + (floor(sourcePixel - vec2(0.0001)) + vec2(0.5)) / atlas;
    }
    if (dialogmenuMarked == 3) discard;
    if (dialogmenuMarked != 0
        && (dialogmenuSlice.x < dialogmenuSlice.y || dialogmenuSlice.x >= dialogmenuSlice.z)) discard;
    return uv;
}
