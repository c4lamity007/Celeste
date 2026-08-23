#version 330

#moj_import <celeste:common.glsl>

uniform sampler2D Sampler0;

in vec2 FragCoord;
in vec2 TexCoord;
in vec4 FragColor;

out vec4 fragColor;

void main() {
    float alpha = roundedAlpha(uSize, FragCoord, uRadius, uSmoothness);
    vec4 color = texture(Sampler0, TexCoord) * FragColor;
    color.a *= alpha;
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
