#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <dialogmenu:rgba_palette.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif
out vec4 vertexColor;
out vec2 texCoord0;
#ifdef IS_GUI
uniform sampler2D Sampler0;
flat out int dialogmenuMarked;
flat out vec4 dialogmenuImagePixels;
out vec2 dialogmenuImageCoord;
out vec3 dialogmenuSlice;
#moj_import <dialogmenu:rgba_vertex.glsl>
#endif

void main() {
    vec3 position = Position;
    vec2 imageUv = UV0;
    vertexColor = Color;
#ifdef IS_GUI
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0.0);
    dialogmenuImageCoord = vec2(0.0);
    dialogmenu_transform(position, imageUv);
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    vertexColor *= sample_lightmap(Sampler2, UV2);
#endif
    texCoord0 = imageUv;
}
