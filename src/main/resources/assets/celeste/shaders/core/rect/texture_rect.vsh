#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <celeste:common.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 FragCoord;
out vec2 TexCoord;
out vec4 FragColor;

void main() {
    FragCoord = rectangleCoordinate(gl_VertexID);
    TexCoord = UV0;
    FragColor = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
