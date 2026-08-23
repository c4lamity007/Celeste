#version 330

#moj_import <celeste:common.glsl>

in vec2 FragCoord;
in vec4 FragColor;

out vec4 fragColor;

void main() {
    float alpha = roundedAlpha(uSize, FragCoord, uRadius, uSmoothness);
    vec4 color = vec4(FragColor.rgb, FragColor.a * alpha);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
