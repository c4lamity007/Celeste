#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <celeste:common.glsl>

in vec3 Position;
in vec4 Color;

out vec2 FragCoord;

void main() {
    FragCoord = rectangleCoordinate(gl_VertexID);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
