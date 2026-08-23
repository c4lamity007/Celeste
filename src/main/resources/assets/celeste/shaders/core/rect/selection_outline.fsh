#version 330

#moj_import <celeste:common.glsl>

in vec2 FragCoord;
in vec4 FragColor;

out vec4 fragColor;

#define uPhase (uShape.w)
#define uSelectionThickness (uTopLeftColor.x)
#define uSelectionOpacity (uTopLeftColor.y)

const float TAU = 6.28318530718;

void main() {
    vec2 center = uSize * 0.5;
    vec2 local = FragCoord * uSize - center;
    float distanceToEdge = roundedDistance(-local, center - 1.0, uRadius);

    float outerCoverage = 1.0 - smoothstep(
        1.0 - uSmoothness,
        1.0,
        distanceToEdge
    );
    float innerCoverage = smoothstep(
        -uSelectionThickness - uSmoothness * 0.5,
        -uSelectionThickness + uSmoothness * 0.5,
        distanceToEdge
    );
    float ringCoverage = outerCoverage * innerCoverage;

    // GUI coordinates grow downward, so an increasing atan phase travels
    // clockwise on screen.
    float angle = fract(atan(local.y, local.x) / TAU + 1.0);
    float phaseDistance = abs(fract(angle - uPhase + 0.5) - 0.5);
    float highlight = 1.0 - smoothstep(0.035, 0.245, phaseDistance);
    float highlightCore = 1.0 - smoothstep(0.0, 0.065, phaseDistance);
    highlight = max(highlight * 0.72, highlightCore);

    vec3 gray = vec3(0.56);
    vec3 rgb = mix(gray, vec3(1.0), highlight);
    float alpha = mix(0.32, 0.94, highlight)
        * clamp(uSelectionOpacity, 0.0, 1.0)
        * ringCoverage
        * FragColor.a;

    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(rgb * FragColor.rgb, alpha);
}
