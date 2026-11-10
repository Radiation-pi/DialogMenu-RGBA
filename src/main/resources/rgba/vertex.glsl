void dialogmenu_slice(float y, int band) {
    float start = band == 0 ? 0.0 : 9.0;
    dialogmenuSlice = vec3(y, start, start + 9.0);
}

bool dialogmenu_native_scope(vec3 position) {
    mat4 projection = ProjMat * ModelViewMat;
    float centerX = -projection[3].x / projection[0].x;
    return abs(position.x + 65536.0 - centerX) < 1024.0;
}

void dialogmenu_transform(inout vec3 position, inout vec2 imageUv) {
    ivec4 samplePixel = ivec4(round(texelFetch(Sampler0, ivec2(UV0 * textureSize(Sampler0, 0)), 0) * 255.0));
    ivec3 texel = samplePixel.rgb;
    ivec4 rgba = ivec4(round(Color * 255.0));
    uint payload = (uint(rgba.a) << 24u) | (uint(rgba.r) << 16u) | (uint(rgba.g) << 8u) | uint(rgba.b);
    bool hidden = all(equal(rgba, ivec4(190, 239, 1, 255)));
    bool nativeScope = dialogmenu_native_scope(position);
    // Color is payload only after a resource/position marker has identified this draw.
    if (hidden && nativeScope) {
        dialogmenuMarked = 3;
        return;
    }
    bool tagged = (payload & 0xE0000000u) == 0x20000000u;
    bool direct = (payload & 0xE0000000u) == 0xC0000000u;
    vec2 edge = step(vec2(0.5), fract(UV0 * vec2(textureSize(Sampler0, 0))));
    if (all(equal(texel.rg, ivec2(29, 85))) && texel.b >= 149 && texel.b <= 152) {
        ivec2 pixel = ivec2(UV0 * textureSize(Sampler0, 0));
        ivec4 meta = ivec4(round(texelFetch(Sampler0, pixel + ivec2(edge.x == 0.0 ? 1 : -1, 0), 0) * 255.0));
        int geometry = int((payload >> 17u) & 16383u) - 1;
        if (meta.b == 177 && hidden) {
            dialogmenuMarked = 3;
            return;
        }
        if (meta.b == 177 && geometry >= 0 && geometry < 10432) {
            int height = int(floor((sqrt(float(8 * geometry + 1225)) - 33.0) / 2.0));
            int row = geometry - (height - 1) * (height + 34) / 2;
            int band = row >= height + 8 ? 0 : 1;
            int top = band == 0 ? row - height - 8 : row - height + 10;
            vec2 target = vec2(float(((payload >> 8u) & 511u) + 1u), float(height));
            vec2 content = vec2(meta.rg);
            float cell = exp2(ceil(log2(max(content.x, content.y) + 2.0)));
            vec2 origin = vec2(pixel) - edge * (cell - 1.0) + vec2(1.0);
            position.xy += vec2(-1.0, float(top) - 8.0) + edge * (target - vec2(1.0));
            if ((payload & 0x80000000u) != 0u) position.x += 1.0 - edge.y * target.y / 4.0;
            imageUv = (origin + edge * content) / vec2(textureSize(Sampler0, 0));
            dialogmenuImagePixels = vec4(origin, content / target)
                / vec4(vec2(textureSize(Sampler0, 0)), vec2(textureSize(Sampler0, 0)));
            dialogmenuImageCoord = edge * target;
            vertexColor = vec4(dialogmenu_color(int(payload & 255u)), 1.0);
            dialogmenuMarked = 2;
            dialogmenu_slice(float(top) + edge.y * target.y, band);
            return;
        }
    }
    if (!tagged && !direct && !hidden) return;
    // Native-atlas object sprites preserve complete 256px sources, unlike bitmap cells with borders.
    if (tagged || hidden) {
        ivec2 atlasSize = textureSize(Sampler0, 0);
        ivec2 endpoint = ivec2(round(UV0 * vec2(atlasSize)));
        for (int corner = 0; corner < 4; corner++) {
            vec2 side = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
            ivec2 pixel = endpoint - ivec2(side);
            if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, atlasSize))) continue;
            ivec4 marker = ivec4(round(texelFetch(Sampler0, pixel, 0) * 255.0));
            if (!all(equal(marker, ivec4(29, 84, 149 + corner, 252 + corner)))) continue;
            ivec4 metadata = ivec4(round(texelFetch(Sampler0, pixel + ivec2(side.x == 0.0 ? 1 : -1, 0), 0) * 255.0));
            if (metadata.b != 177) continue;
            if (hidden) {
                dialogmenuMarked = 3;
                return;
            }
            vec2 contentSize = vec2(metadata.rg + ivec2(1));
            vec2 cellSize = exp2(ceil(log2(contentSize + vec2(4.0))));
            vec2 target = vec2(float(((payload >> 19u) & 511u) + 1u), float(((payload >> 10u) & 511u) + 1u));
            int top = int(payload & 1023u);
            if (top >= 512) top -= 1024;
            vec2 origin = vec2(pixel) - side * (cellSize - vec2(1.0)) + vec2(2.0);
            position.xy += vec2(-1.0, float(top)) + side * (target - vec2(8.0));
            imageUv = (origin + side * contentSize) / vec2(atlasSize);
            dialogmenuImagePixels = vec4(origin, contentSize / target) / vec4(vec2(atlasSize), vec2(atlasSize));
            dialogmenuImageCoord = side * target;
            vertexColor = vec4(dialogmenu_color(metadata.a), 1.0);
            dialogmenuMarked = 2;
            dialogmenu_slice(float(top) + side.y * target.y, int((payload >> 28u) & 1u));
            return;
        }
    }
    // FontTexture insets bitmap UV endpoints by 0.01 texel, independently of draw offsets.
    if (all(equal(texel, ivec3(23, 71, 131))) && (tagged || hidden)) {
        if (hidden) {
            dialogmenuMarked = 3;
            return;
        }
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
        && (tagged || hidden)) {
        ivec2 pixel = ivec2(UV0 * textureSize(Sampler0, 0));
        ivec4 metadata = ivec4(round(texelFetch(Sampler0, pixel + ivec2(edge.x == 0.0 ? 1 : -1, 0), 0) * 255.0));
        if (metadata.b == 177) {
            if (hidden) {
                dialogmenuMarked = 3;
                return;
            }
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
    if (direct && nativeScope) {
        position.x += 65536.0;
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
