layout(location = 7) flat out float dialogmenuModelVisible;

void dialogmenu_model(inout vec3 position, inout vec2 imageUv) {
    dialogmenuModelVisible = 1.0;
#ifdef IS_GUI
    if (dialogmenuMarked != 0) return;
    uvec4 bytes = uvec4(round(Color * 255.0));
    uint payload = (bytes.a << 24u) | (bytes.r << 16u) | (bytes.g << 8u) | bytes.b;
    if ((payload & 0xE0000000u) == 0x60000000u && dialogmenu_native_scope(position)) {
        position.x += 65536.0;
        int corner = (gl_VertexIndex - gl_BaseVertex) % 4;
        vec2 edge = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
        vec2 size = vec2(float(((payload >> 19u) & 511u) + 1u),
                        float(((payload >> 10u) & 511u) + 1u));
        int localY = int(payload & 1023u);
        if (localY >= 512) localY -= 1024;
        position.xy += vec2(-1.0, float(localY)) + edge * (size - vec2(8.0));
        vertexColor = vec4(1.0);
        dialogmenuMarked = 4;
        dialogmenu_slice(float(localY) + edge.y * size.y, int((payload >> 28u) & 1u));
        return;
    }
    // Balanced negative/positive spaces carry the face without copying pixels.
    mat4 projection = ProjMat * ModelViewMat;
    float centerX = -projection[3].x / projection[0].x;
    int face = int(floor((centerX - position.x) / 2000.0 + 0.5)) - 33;
    bool ce = face >= 4096;
    int record = ce ? face - 4096 : face;
    if (record < 0 || record >= (ce ? MODEL_CE_FACES : MODEL_VANILLA_FACES)) return;
    if (all(equal(bytes, uvec4(190, 239, 1, 255)))) {
        dialogmenuMarked = 3;
        return;
    }
    if ((payload & 0xE0000000u) != 0x40000000u) return;
    position.x += float((face + 33) * 2000);
    int corner = (gl_VertexIndex - gl_BaseVertex) % 4;
    vec2 edge = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
    ivec2 atlasSize = textureSize(Sampler0, 0);
    vec4 data = ce ? modelCeData(record * 5 + corner) : modelVanillaData(record * 5 + corner);
    vec2 size = vec2(float(((payload >> 19u) & 511u) + 1u),
                    float(((payload >> 10u) & 511u) + 1u));
    int localY = int(payload & 1023u);
    if (localY >= 512) localY -= 1024;
    vec2 origin = position.xy - edge * 8.0 - vec2(1.0);
    position.xy = origin + data.xy * size + vec2(0.0, float(localY));
    imageUv += data.zw / vec2(atlasSize);
    vertexColor = ce ? modelCeData(record * 5 + 4) : modelVanillaData(record * 5 + 4);
    dialogmenuMarked = 4;
    dialogmenu_slice(data.y * size.y + float(localY) - 1.0, int((payload >> 28u) & 1u));
#endif
}
