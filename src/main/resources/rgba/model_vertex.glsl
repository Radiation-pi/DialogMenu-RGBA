layout(location = 7) flat out float dialogmenuModelVisible;

void dialogmenu_model(inout vec3 position, inout vec2 imageUv) {
    dialogmenuModelVisible = 1.0;
#ifdef IS_GUI
    uvec4 bytes = uvec4(round(Color * 255.0));
    if (bytes.a == 255u && bytes.r == 190u && bytes.g == 239u && bytes.b == 1u) {
        dialogmenuModelVisible = 0.0;
        return;
    }
    if (dialogmenuMarked != 0) return;
    uint marker = bytes.a & 192u;
    if (marker != 64u && marker != 128u) return;
    uint modelPayload = (bytes.a << 24u) | (bytes.r << 16u) | (bytes.g << 8u) | bytes.b;
    int face = int((modelPayload >> 17u) & 8191u);
    int size = int((modelPayload >> 10u) & 127u);
    bool ce = face >= 4096;
    int localFace = ce ? face - 4096 : face;
    if (localFace >= (ce ? MODEL_CE_FACES : MODEL_VANILLA_FACES) || size < 1) return;
    int offsetY = int(modelPayload & 1023u) - 512;
    ivec2 pixel = ivec2(round(UV0 * vec2(textureSize(Sampler0, 0))));
    ivec3 tag = ivec3(ce ? modelCeData[localFace * 6 + 5].rgb : modelVanillaData[localFace * 6 + 5].rgb);
    int corner = -1;
    for (int candidate = 0; candidate < 4; candidate++) {
        ivec2 offset = ivec2(candidate >= 2 ? -1 : 0, candidate == 1 || candidate == 2 ? -1 : 0);
        ivec2 location = pixel + offset;
        if (any(lessThan(location, ivec2(0))) || any(greaterThanEqual(location, textureSize(Sampler0, 0)))) continue;
        ivec4 markerPixel = ivec4(round(texelFetch(Sampler0, location, 0) * 255.0));
        if (all(equal(markerPixel, ivec4(tag, 252 + candidate)))) corner = candidate;
    }
    if (corner < 0) {
        dialogmenuModelVisible = 0.0;
        return;
    }
    vec2 edge = vec2(corner >= 2 ? 1.0 : 0.0,
        corner == 1 || corner == 2 ? 1.0 : 0.0);
    vec4 data = ce ? modelCeData[localFace * 6 + corner] : modelVanillaData[localFace * 6 + corner];
    vec2 origin = position.xy - edge * 8.0 - vec2(1.0);
    position.xy = origin + data.xy * float(size) - vec2(0.0, float(offsetY));
    imageUv += data.zw / vec2(textureSize(Sampler0, 0));
    vertexColor = ce ? modelCeData[localFace * 6 + 4] : modelVanillaData[localFace * 6 + 4];
    dialogmenuMarked = 4;
    dialogmenu_slice(data.y * float(size) - float(offsetY) - 1.0, marker == 64u ? 1 : 0);
#endif
}
