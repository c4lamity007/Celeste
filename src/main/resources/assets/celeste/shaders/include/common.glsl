#version 330

layout(std140) uniform CelesteParams {
    vec4 uRadius;
    vec4 uShape;
    vec4 uTopLeftColor;
    vec4 uBottomLeftColor;
    vec4 uBottomRightColor;
    vec4 uTopRightColor;
    vec4 uText;
    vec4 uOutlineColor;
};

#define uSize (uShape.xy)
#define uSmoothness (uShape.z)
#define uMix (uShape.w)
#define uAlpha (uText.x)
#define uRange (uText.x)
#define uThickness (uText.y)
#define uOutline (uText.z)
#define uOutlineThickness (uText.w)

float roundedDistance(vec2 position, vec2 size, vec4 radius) {
    radius.xy = position.x > 0.0 ? radius.xy : radius.wz;
    radius.x = position.y > 0.0 ? radius.x : radius.y;

    vec2 delta = abs(position) - size + radius.x;
    return min(max(delta.x, delta.y), 0.0) + length(max(delta, 0.0)) - radius.x;
}

float roundedAlpha(vec2 size, vec2 coordinate, vec4 radius, float smoothness) {
    vec2 center = size * 0.5;
    float distance = roundedDistance(center - coordinate * size, center - 1.0, radius);
    if (smoothness <= 0.0) {
        return distance <= 0.0 ? 1.0 : 0.0;
    }
    return 1.0 - smoothstep(1.0 - smoothness, 1.0, distance);
}

const vec2[4] RECT_VERTEX_COORDINATES = vec2[](
    vec2(0.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0),
    vec2(1.0, 0.0)
);

vec2 rectangleCoordinate(int vertexId) {
    return RECT_VERTEX_COORDINATES[vertexId % 4];
}
