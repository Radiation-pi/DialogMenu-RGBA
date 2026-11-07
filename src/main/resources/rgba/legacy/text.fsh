#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
flat in int dialogmenuMarked;
flat in vec4 dialogmenuImagePixels;
in vec2 dialogmenuImageCoord;
out vec4 fragColor;

#moj_import <dialogmenu:rgba_fragment.glsl>

void main() {
    vec4 texColor = texture(Sampler0, dialogmenu_uv());
    if (dialogmenuMarked == 1) texColor.rgb = vec3(1.0);
    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.1) discard;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
