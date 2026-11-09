#version 460
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
layout(location = 8) out vec3 dialogmenuSlice;
#endif

#ifdef IS_GUI
#include <dialogmenu:rgba_vertex.glsl>
#endif

void main() {
    vec3 position = Position;
    vec2 imageUv = UV0;
    vertexColor = Color;
#ifdef IS_GUI
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0.0);
    dialogmenuImageCoord = vec2(0.0);
    dialogmenuSlice = vec3(0.0);
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
