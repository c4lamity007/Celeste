#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float r = length(p);
    float angle = atan(p.y, p.x);
    float time = GameTime * 1200.0;
    float aa = max(fwidth(r) * 1.5, 0.002);
    float ring = 1.0 - smoothstep(0.009, 0.009 + aa, abs(r - 0.74));
    float inner = 1.0 - smoothstep(0.004, 0.004 + aa, abs(r - 0.59));
    float arcs = smoothstep(-0.1, 0.2, sin(angle * 3.0 - time * 1.25));
    float halo = texture(Sampler0, texCoord).a * 0.16;
    float sweep = pow(max(0.0, cos(angle - time * 0.85)), 16.0);
    float alpha = tint.a * (ring * (0.3 + 0.65 * arcs) + inner * sweep * 0.6 + halo);
    if (alpha < 0.003 || r > 0.98) discard;
    fragColor = vec4(mix(tint.rgb, vec3(1.0), sweep * 0.25), min(alpha, 0.85));
}
