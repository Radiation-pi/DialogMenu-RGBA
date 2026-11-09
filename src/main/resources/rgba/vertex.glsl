void dialogmenu_slice(float y, int band) {
    float start = band == 0 ? 0.0 : 9.0;
    dialogmenuSlice = vec3(y, start, start + 9.0);
}

void dialogmenu_transform(inout vec3 position, inout vec2 imageUv) {
    ivec4 samplePixel = ivec4(round(texelFetch(Sampler0, ivec2(UV0 * textureSize(Sampler0, 0)), 0) * 255.0));
    ivec3 texel = samplePixel.rgb;
    ivec4 rgba = ivec4(round(Color * 255.0));
    uint payload = (uint(rgba.a) << 24u) | (uint(rgba.r) << 16u) | (uint(rgba.g) << 8u) | uint(rgba.b);
    if (all(equal(rgba, ivec4(190, 239, 1, 255)))) {
        dialogmenuMarked = 3;
        return;
    }
    bool tagged = (payload & 0xE0000000u) == 0x20000000u;
    bool direct = (payload & 0xE0000000u) == 0xC0000000u;
    if (!tagged && !direct) return;
    // FontTexture insets bitmap UV endpoints by 0.01 texel, independently of draw offsets.
    vec2 edge = step(vec2(0.5), fract(UV0 * vec2(textureSize(Sampler0, 0))));
    if (all(equal(texel, ivec3(23, 71, 131))) && tagged) {
        int top = int((payload >> 8u) & 1023u);
        if (top >= 512) top -= 1024;
        int height = int((payload >> 18u) & 127u) + 1;
        dialogmenuMarked = 1;
        position.xy += vec2(-1.0, float(top) - 8.0);
        vertexColor = vec4(dialogmenu_color(int(payload & 255u)), 1.0);
        dialogmenu_slice(float(top) + edge.y * float(height), int((payload >> 28u) & 1u));
        return;
    }
    if (all(equal(texel.rg, ivec2(29, 83))) && texel.b >= 149 && texel.b <= 152
        && tagged) {
        ivec2 pixel = ivec2(UV0 * textureSize(Sampler0, 0));
        ivec4 metadata = ivec4(round(texelFetch(Sampler0, pixel + ivec2(edge.x == 0.0 ? 1 : -1, 0), 0) * 255.0));
        if (metadata.b == 177) {
            vec2 contentSize = vec2(metadata.rg);
            float cellSize = float(samplePixel.a + 1);
            vec2 target = vec2(float(((payload >> 19u) & 511u) + 1u), float(((payload >> 10u) & 511u) + 1u));
            int localY = int(payload & 1023u);
            if (localY >= 512) localY -= 1024;
            position.xy += vec2(-1.0, float(localY) - 8.0);
            position.xy += edge * (target - vec2(1.0));
            imageUv += (vec2(1.0) - edge * (vec2(cellSize) - contentSize)) / vec2(textureSize(Sampler0, 0));
            ivec2 origin = pixel - ivec2(edge * (cellSize - 1.0));
            dialogmenuImagePixels = vec4(vec2(origin + ivec2(1)), contentSize / target)
                / vec4(vec2(textureSize(Sampler0, 0)), vec2(textureSize(Sampler0, 0)));
            dialogmenuImageCoord = edge * target;
            vertexColor = vec4(dialogmenu_color(metadata.a), 1.0);
            dialogmenuMarked = 2;
            dialogmenu_slice(float(localY) + edge.y * target.y, int((payload >> 28u) & 1u));
            return;
        }
    }
    // 原版/CE纹理保持直接引用，任意比例回退图也只改定位与行裁切。
    if (direct) {
        int top = int((payload >> 8u) & 2047u);
        if (top >= 1024) top -= 2048;
        int band = top >= 32 ? 0 : 1;
        if (band == 0) top -= 32;
        int height = int((payload >> 19u) & 1023u) + 1;
        position.xy += vec2(-1.0, float(top) - 8.0);
        vertexColor = vec4(dialogmenu_color(int(payload & 255u)), 1.0);
        dialogmenuMarked = 4;
        dialogmenu_slice(float(top) + edge.y * float(height), band);
    }
}
