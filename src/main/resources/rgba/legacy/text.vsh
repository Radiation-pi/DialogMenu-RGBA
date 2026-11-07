#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <dialogmenu:rgba_palette.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
flat out int dialogmenuMarked;
flat out vec4 dialogmenuImagePixels;
out vec2 dialogmenuImageCoord;

#moj_import <dialogmenu:rgba_vertex.glsl>

void main() {
    vec3 position = Position;
    vec2 imageUv = UV0;
    vertexColor = Color;
    dialogmenuMarked = 0;
    dialogmenuImagePixels = vec4(0.0);
    dialogmenuImageCoord = vec2(0.0);
    // 旧版 GUI 与世界文字共用 pipeline；透视投影不解码 RGBA。
    if (ProjMat[3][3] == 1.0) dialogmenu_transform(position, imageUv);
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    vertexColor *= texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = imageUv;
}
