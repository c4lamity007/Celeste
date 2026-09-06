#version 330
#moj_import <minecraft:fog.glsl>

in vec2 texCoord;
in vec4 tint;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 viewPosition;
in vec2 fogDistance;
out vec4 fragColor;

void main() {
    float diffuse = max(dot(normalize(worldNormal), normalize(vec3(-0.35, 0.82, 0.45))), 0.0);
    float facing = abs(dot(normalize(viewNormal), normalize(-viewPosition)));
    float edge = pow(1.0 - clamp(facing, 0.0, 1.0), 2.0);
    // Broad facet lighting, a pearly core and a restrained rim: no screen-space stripes.
    vec3 color = mix(tint.rgb * (0.35 + diffuse * 0.55), vec3(0.9,0.97,1.0), 0.17 + edge * 0.32);
    color += tint.rgb * 0.15;
    if (tint.a < 0.003) discard;
    fragColor = apply_fog(vec4(color, tint.a), fogDistance.x, fogDistance.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
