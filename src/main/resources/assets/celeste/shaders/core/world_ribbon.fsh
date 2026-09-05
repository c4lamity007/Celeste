#version 330
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 tint;
out vec4 fragColor;

void main() {
    // GameTime is updated with partial ticks by the game, not per module tick.
    float time = GameTime * 1200.0;
    vec2 uv = texCoord;
    // The silhouette comes from continuous analytic strands, not individual PNG
    // stamps. Shared distance UVs make their phase run through every mesh join.
    float warp = sin(uv.x * 12.56637 - time * 1.3) * 0.022;
    vec4 fibers = texture(Sampler0, vec2(uv.x, clamp(uv.y + warp, 0.0, 1.0)));
    float energy = dot(fibers.rgb, vec3(0.299, 0.587, 0.114));
    float y = uv.y - 0.5;
    float phase = uv.x * 18.849556 - time * 2.0;
    float braidA = y - sin(phase) * 0.095;
    float braidB = y + sin(phase + 0.8) * 0.095;
    float aa = max(fwidth(y) * 0.75, 0.002);
    float core = 1.0 - smoothstep(0.012, 0.03 + aa, abs(y));
    float strandA = 1.0 - smoothstep(0.003, 0.017 + aa, abs(braidA));
    float strandB = 1.0 - smoothstep(0.003, 0.017 + aa, abs(braidB));
    float sheath = exp(-y * y * 85.0) * 0.09;
    float current = 0.75 + 0.25 * sin(uv.x * 25.132741 - time * 3.0);
    float imageDetail = fibers.a * smoothstep(0.035, 0.8, energy) * 0.38;
    float edges = smoothstep(0.0, 0.13, uv.y) * (1.0 - smoothstep(0.87, 1.0, uv.y));
    float alpha = tint.a * edges * (core * 0.24 + (strandA + strandB) * 0.23 * current + sheath + imageDetail);
    vec3 color = mix(tint.rgb, vec3(1.0), min(0.33, core * 0.21 + max(strandA, strandB) * 0.12));
    if (alpha < 0.003) discard;
    fragColor = vec4(color, min(alpha, 0.8));
}
