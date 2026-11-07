#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
#ifdef IS_GUI
layout(location = 4) flat in int dialogmenuMarked;
layout(location = 5) flat in vec4 dialogmenuImagePixels;
layout(location = 6) in vec2 dialogmenuImageCoord;
#endif
#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

#ifdef IS_GUI
#include <dialogmenu:rgba_fragment.glsl>
#endif

vec4 calculateFinalColor(vec4 color) {
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif
#if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)
#ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
#else
    vec4 fogColor = FogColor;
#endif
    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
    return color;
}

void main() {
    vec2 uv = texCoord0;
#ifdef IS_GUI
    uv = dialogmenu_uv();
#endif
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, uv).rrrr;
#else
    vec4 texColor = texture(Sampler0, uv);
#endif
#ifdef IS_GUI
    if (dialogmenuMarked == 1) texColor.rgb = vec3(1.0);
#endif
    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.1) discard;
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    fragColor = calculateFinalColor(color);
#endif
}
