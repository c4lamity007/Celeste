#version 330

#moj_import <celeste:common.glsl>

uniform sampler2D Sampler0;

in vec2 FragCoord;
in vec2 TexCoord;

out vec4 fragColor;

vec4 gradient(vec2 uv) {
    vec4 top = mix(uTopLeftColor, uTopRightColor, uv.x);
    vec4 bottom = mix(uBottomLeftColor, uBottomRightColor, uv.x);
    return mix(top, bottom, uv.y);
}

void main() {
    float shapeAlpha = roundedAlpha(uSize, FragCoord, uRadius, uSmoothness);
    if (shapeAlpha <= 0.0) {
        discard;
    }

    vec3 blurred = texture(Sampler0, TexCoord).rgb;
    vec3 tint = gradient(FragCoord).rgb;

    // Tint amount and panel opacity are independent. This avoids multiplying
    // the tint alpha into itself as the original implementation did.
    fragColor = vec4(mix(blurred, tint, uMix), shapeAlpha * uAlpha);
}
