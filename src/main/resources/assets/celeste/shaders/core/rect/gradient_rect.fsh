#version 330

#moj_import <celeste:common.glsl>

in vec2 FragCoord;

out vec4 fragColor;

vec4 gradient(vec2 uv) {
    vec4 top = mix(uTopLeftColor, uTopRightColor, uv.x);
    vec4 bottom = mix(uBottomLeftColor, uBottomRightColor, uv.x);
    return mix(top, bottom, uv.y);
}

void main() {
    vec4 fill = gradient(FragCoord);
    float alpha = roundedAlpha(uSize, FragCoord, uRadius, uSmoothness);
    vec4 color = vec4(fill.rgb, fill.a * alpha);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
