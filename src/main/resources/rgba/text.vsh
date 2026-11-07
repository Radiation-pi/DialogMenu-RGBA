#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <dialogmenu:rgba_palette.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
#ifdef IS_GUI
uniform sampler2D Sampler0;
layout(location = 4) flat out int dialogmenuMarked;
layout(location = 5) flat out vec4 dialogmenuImagePixels;
layout(location = 6) out vec2 dialogmenuImageCoord;
#endif

void main() {
    vec3 position = Position;
    vec2 imageUv = UV0;
    vertexColor = Color;
#ifdef IS_GUI
    ivec4 samplePixel = ivec4(round(texelFetch(Sampler0, ivec2(UV0 * textureSize(Sampler0, 0)), 0) * 255.0));
    ivec3 texel = samplePixel.rgb;
    ivec4 rgba = ivec4(round(Color * 255.0));
    int payload = (rgba.r << 16) | (rgba.g << 8) | rgba.b;
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0.0);
    dialogmenuImageCoord = vec2(0.0);
    if (all(equal(texel, ivec3(23, 71, 131))) && (payload & 0xFC0000) == 0xD40000 && rgba.a == 255) {
        dialogmenuMarked = 1;
        position.y += float((payload >> 8) & 1023) - 7.0;
        vertexColor.rgb = dialogmenu_color(payload & 255);
    }
    if (all(equal(texel.rg, ivec2(29, 83))) && texel.b >= 149 && texel.b <= 152
        && (payload & 0x800000) != 0 && rgba.a == 255) {
        int corner = texel.b - 149;
        vec2 edge = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
        ivec2 pixel = ivec2(UV0 * textureSize(Sampler0, 0));
        ivec3 metadata = ivec3(round(texelFetch(Sampler0, pixel + ivec2(edge.x == 0.0 ? 1 : -1, 0), 0).rgb * 255.0));
        if (metadata.b == 177) {
            vec2 contentSize = vec2(metadata.rg);
            float cellSize = float(samplePixel.a + 1);
            vec2 target = vec2(float(((payload >> 14) & 511) + 1), float(((payload >> 5) & 511) + 1));
            int localY = payload & 31;
            if (localY >= 16) localY -= 32;
            position.y += float(localY) - 7.0;
            position.xy += edge * (target - vec2(1.0));
            imageUv += (vec2(1.0) - edge * (vec2(cellSize) - contentSize)) / vec2(textureSize(Sampler0, 0));
            ivec2 origin = pixel - ivec2(edge * (cellSize - 1.0));
            dialogmenuImagePixels = vec4(vec2(origin + ivec2(1)), contentSize / target)
                / vec4(vec2(textureSize(Sampler0, 0)), vec2(textureSize(Sampler0, 0)));
            dialogmenuImageCoord = edge * target;
            vertexColor.rgb = vec3(1.0);
            dialogmenuMarked = 2;
        }
    }
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    vertexColor *= sample_lightmap(Sampler2, UV2);
#endif
    texCoord0 = imageUv;
}
