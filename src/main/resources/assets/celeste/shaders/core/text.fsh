#version 330

#moj_import <celeste:common.glsl>

uniform sampler2D Sampler0;

in vec2 TexCoord;
in vec4 FragColor;

out vec4 fragColor;

float median(vec3 value) {
    return max(min(value.r, value.g), min(max(value.r, value.g), value.b));
}

void main() {
    float distance = median(texture(Sampler0, TexCoord).rgb) - 0.5 + uThickness;
    vec2 derivatives = vec2(dFdx(TexCoord.x), dFdy(TexCoord.y)) * textureSize(Sampler0, 0);
    float pixels = uRange * inversesqrt(dot(derivatives, derivatives));
    float alpha = smoothstep(-uSmoothness, uSmoothness, distance * pixels);
    vec4 color = vec4(FragColor.rgb, FragColor.a * alpha);

    if (uOutline > 0.5) {
        color = mix(uOutlineColor, FragColor, alpha);
        color.a *= smoothstep(-uSmoothness, uSmoothness, (distance + uOutlineThickness) * pixels);
    }

    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
