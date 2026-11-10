vec2 dialogmenu_uv() {
    vec2 uv = texCoord0;
    if (dialogmenuMarked == 2 || dialogmenuMarked == 5) {
        vec2 atlas = vec2(textureSize(Sampler0, 0));
        // Sample the full source at screen fragments, not an intermediate logical-pixel grid.
        vec2 sourcePixel = dialogmenuImageCoord * dialogmenuImagePixels.zw * atlas;
        if (dialogmenuMarked == 5) {
            // Snap numerical interpolation error at exact texel boundaries, in physical-pixel units.
            vec2 boundary = round(sourcePixel);
            vec2 pixelStep = abs(vec2(dFdx(sourcePixel.x), dFdy(sourcePixel.y)));
            sourcePixel = mix(sourcePixel, boundary,
                lessThan(abs(sourcePixel - boundary), pixelStep * 0.001));
        }
        // 恰好落在采样边界时取前一个纹素，再采样其中心。
        uv = dialogmenuImagePixels.xy + (floor(sourcePixel - vec2(0.0001)) + vec2(0.5)) / atlas;
    }
    if (dialogmenuMarked == 3) discard;
    if (dialogmenuMarked != 0
        && (dialogmenuSlice.x < dialogmenuSlice.y || dialogmenuSlice.x >= dialogmenuSlice.z)) discard;
    return uv;
}
