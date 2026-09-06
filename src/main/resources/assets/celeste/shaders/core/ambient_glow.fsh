#version 330
#moj_import <minecraft:fog.glsl>

in vec2 texCoord;
in vec4 tint;
in vec2 fogDistance;
out vec4 fragColor;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float radius = dot(p,p);
    float falloff = exp(-radius * 5.5) * (1.0 - smoothstep(0.55,1.0,radius));
    float fog = total_fog_value(fogDistance.x, fogDistance.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float alpha = tint.a * falloff * (1.0 - fog);
    if (alpha < 0.002) discard;
    fragColor = vec4(tint.rgb, alpha);
}
