#version 330
uniform sampler2D Sampler0;
in vec2 texCoord;
in vec2 localCoord;
in vec4 textColor;
flat in float wordPhase;
flat in float wordSeed;
flat in float vaporLayer;
out vec4 fragColor;
float median(vec3 s) { return max(min(s.r, s.g), min(max(s.r, s.g), s.b)); }
float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * .1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}
float noise(vec2 p) {
    vec2 cell = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1,0)), f.x),
        mix(hash(cell + vec2(0,1)), hash(cell + vec2(1,1)), f.x), f.y);
}
void main() {
    // RGB supplies sharp MTSDF corners; alpha supplies a true-distance halo.
    vec4 sdf = texture(Sampler0, texCoord);
    vec2 footprint = max(fwidth(texCoord), vec2(1e-7));
    float range = max(.5 * dot(vec2(12.0) / vec2(textureSize(Sampler0,0)), 1.0 / footprint), 1.0);
    float distance = (median(sdf.rgb) - .5) * range;
    float fill = smoothstep(-.55, .55, distance);
    float halo = exp(-max(0.0, (.5 - sdf.a) * range) * .95) * .17;
    if (max(fill, halo) < .002 || textColor.a <= 0.0) discard;
    vec2 seed = vec2(wordSeed * 53.0, wordSeed * 97.0);
    float alpha; vec3 color = textColor.rgb;
    if (vaporLayer < .5) {
        float progress = clamp((wordPhase - .25) / .75, 0.0, 1.0);
        float height = clamp((localCoord.y + .24) / 1.2, 0.0, 1.0);
        float pore = noise(localCoord * vec2(19.0, 22.0) + seed);
        float boundary = .14 + .51 * height + .25 * pore;
        float retained = wordPhase <= .25 ? 1.0 : smoothstep(progress - .07, progress + .07, boundary);
        if (wordPhase >= 1.0) retained = 0.0;
        float edge = (1.0 - smoothstep(.012, .075, abs(boundary - progress)))
            * smoothstep(.25, .4, wordPhase) * (1.0 - smoothstep(.9, 1.0, wordPhase));
        color = mix(color, vec3(1.0), edge * .28);
        alpha = max(fill, halo) * retained;
    } else {
        // Only vertical material flow on the fixed local plane; no camera/screen coordinates.
        float rise = smoothstep(.20, .55, wordPhase) * (1.0 - smoothstep(1.0, 2.0, wordPhase));
        float streak = noise(vec2(localCoord.x * 29.0, localCoord.y * 3.6 - wordPhase * 3.0) + seed);
        float mist = noise(vec2(localCoord.x * 14.0, localCoord.y * 18.0 - wordPhase * 4.5) + seed);
        float strands = smoothstep(.38, .82, streak) * (.5 + .5 * mist);
        alpha = max(fill, halo * .6) * rise * strands * .34;
        color = mix(color, vec3(1.0), .18);
    }
    alpha *= textColor.a;
    if (alpha < .002) discard;
    fragColor = vec4(color, alpha);
}
