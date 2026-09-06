#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
out vec2 texCoord;
out vec4 tint;
out vec3 worldNormal;
out vec3 viewNormal;
out vec3 viewPosition;
out vec2 fogDistance;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord = UV0;
    tint = Color * ColorModulator;
    worldNormal = Normal;
    viewNormal = mat3(ModelViewMat) * Normal;
    viewPosition = view.xyz;
    fogDistance = vec2(length(Position), max(length(Position.xz), abs(Position.y)));
}
