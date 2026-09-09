#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
out vec2 texCoord;
out vec2 localCoord;
out vec4 textColor;
flat out float wordPhase;
flat out float wordSeed;
flat out float vaporLayer;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    localCoord = vec2((float(UV1.x) / 32767.0 - .5) * 68.90625,
        1.5 - float(UV1.y) / 32767.0 * 3.0);
    textColor = Color * ColorModulator;
    wordPhase = clamp(float(UV2.x) / 32767.0 * 2.0, 0.0, 2.0);
    wordSeed = float(UV2.y) / 32767.0;
    vaporLayer = Normal.x;
}
