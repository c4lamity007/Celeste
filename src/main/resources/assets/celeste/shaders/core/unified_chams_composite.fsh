#version 330
#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;
out vec4 fragColor;

float covered(ivec2 pixel, ivec2 size) {
    // texelFetch outside its texture is undefined; cropped silhouettes must
    // not sample the opposite screen edge or uninitialized texture memory.
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size))) return 0.0;
    // Binary coverage, NOT alpha/color/depth differences. A faint trail meeting
    // a strong principal, or two limb faces meeting, must never acquire a seam.
    return step(0.001, texelFetch(InSampler, pixel, 0).a);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    vec4 fill = texelFetch(InSampler, pixel, 0);
    if (fill.a < 0.001) {
        // Preserve coverage exactly and avoid neighborhood work across the
        // empty majority of the framebuffer. No extra blur targets or passes.
        fragColor = vec4(0.0);
        return;
    }

    // Resolution-aware, GUI-scale-independent internal neon rim. Geometry
    // normals and ModelPart boundaries are deliberately not involved.
    int radius = int(floor(clamp(float(size.y) / 600.0, 1.5, 4.0) + 0.5));
    float neighbors = covered(pixel + ivec2(radius, 0), size)
        + covered(pixel + ivec2(-radius, 0), size)
        + covered(pixel + ivec2(0, radius), size)
        + covered(pixel + ivec2(0, -radius), size)
        + covered(pixel + ivec2(radius, radius), size)
        + covered(pixel + ivec2(-radius, radius), size)
        + covered(pixel + ivec2(radius, -radius), size)
        + covered(pixel + ivec2(-radius, -radius), size);
    float rim = clamp((1.0 - neighbors * 0.125) * 2.4, 0.0, 1.0);
    int haloRadius = radius * 3;
    float innerHalo = 1.0 - 0.25 * (covered(pixel + ivec2(haloRadius, 0), size)
        + covered(pixel + ivec2(-haloRadius, 0), size)
        + covered(pixel + ivec2(0, haloRadius), size)
        + covered(pixel + ivec2(0, -haloRadius), size));

    // One continuous field over the resolved silhouette, rather than a new
    // effect per limb. Partial-tick GameTime animates on rendered frames. Both
    // cycles wrap exactly with Minecraft's 20-minute shader clock.
    vec2 screen = gl_FragCoord.xy / float(size.y);
    float phase = screen.y * 1.75 - screen.x * 0.18 - fract(GameTime * 300.0);
    float distanceToSweep = abs(fract(phase + 0.5) - 0.5);
    float sweep = exp(-pow(distanceToSweep / 0.065, 2.0));
    float core = exp(-pow(distanceToSweep / 0.012, 2.0));
    float flow = 0.5 + 0.5 * sin(6.2831853
        * (screen.y * 0.7 + screen.x * 0.35 - fract(GameTime * 240.0)));

    // Keep the configured color and historical color separation visible.
    // The energy band carries a narrow ice-bright core; the broad body remains
    // saturated and quietly shaded. Alpha stays EXACTLY the resolved fill's.
    vec3 body = fill.rgb * (0.84 + flow * 0.26 + sweep * 0.45);
    vec3 energy = mix(fill.rgb, vec3(0.86, 0.96, 1.0), 0.62);
    body = mix(body, energy, core * 0.82);
    vec3 edge = mix(fill.rgb, vec3(0.92, 0.98, 1.0), 0.78 + sweep * 0.12);
    body = mix(body, energy, innerHalo * 0.58);
    fragColor = vec4(clamp(mix(body, edge, rim), 0.0, 1.0), fill.a);
}
