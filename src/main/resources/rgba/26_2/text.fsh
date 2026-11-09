#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif
in vec4 vertexColor;
in vec2 texCoord0;
#ifdef IS_GUI
flat in int dialogmenuMarked;
flat in vec4 dialogmenuImagePixels;
in vec2 dialogmenuImageCoord;
in vec3 dialogmenuSlice;
#moj_import <dialogmenu:rgba_fragment.glsl>
#endif
out vec4 fragColor;

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
#if defined(IS_SEE_THROUGH) || defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
