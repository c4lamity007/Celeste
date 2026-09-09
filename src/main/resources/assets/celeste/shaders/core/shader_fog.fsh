#version 330
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D SceneDepth;
in vec2 texCoord;
in vec4 nearPlane;
in vec4 farPlane;
out vec4 fragColor;

// ColorModulator: sky preset, density, distance, frame-time phase.
// ModelOffset: world camera, X/Z wrapped at 8192 blocks for floating-point precision.
// TextureMat: camera rotation (view -> world). No screen-space animated pattern.
float hash31(vec3 cell) {
    cell = mod(cell, 128.0);
    cell = fract(cell * 0.1031);
    cell += dot(cell, cell.yzx + 33.33);
    return fract((cell.x + cell.y) * cell.z);
}

float volumeNoise(vec3 point) {
    vec3 cell = floor(point), f = fract(point);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(cell), hash31(cell+vec3(1,0,0)), f.x),
                   mix(hash31(cell+vec3(0,1,0)), hash31(cell+vec3(1,1,0)), f.x), f.y),
               mix(mix(hash31(cell+vec3(0,0,1)), hash31(cell+vec3(1,0,1)), f.x),
                   mix(hash31(cell+vec3(0,1,1)), hash31(cell+vec3(1,1,1)), f.x), f.y), f.z);
}

vec3 material(float mode, vec3 direction, float mist, float phase, float worldHeight) {
    if (mode < 0.5) {
        // Pearlescent, broad aurora scattering, not ribbon lines attached to the camera.
        vec3 haze = mix(vec3(0.14,0.30,0.43), vec3(0.34,0.65,0.62), mist);
        float pearl = pow(max(dot(direction,normalize(vec3(-.30,.40,-.86))),0.0), 4.0);
        return haze + vec3(.12,.16,.22) * pearl * (.3 + mist * .55);
    }
    if (mode < 1.5) {
        vec3 haze = mix(vec3(.23,.23,.44), vec3(.57,.38,.63), mist);
        float glow = pow(max(dot(direction,normalize(vec3(-.30,.46,-.84))),0.0), 8.0);
        return haze + vec3(.18,.15,.27) * glow * .6;
    }
    if (mode < 2.5) {
        // The scattering axis is the exact Custom Sky sun direction. A soft,
        // wide glisten follows the sun; it never pans with the camera itself.
        float sun = pow(max(dot(direction,normalize(vec3(.55,.23,-.80))),0.0), 12.0);
        float glisten = pow(max(dot(direction,normalize(vec3(.55,.23,-.80))),0.0), 80.0);
        vec3 haze = mix(vec3(.49,.31,.39), vec3(.91,.60,.35), mist);
        return haze + vec3(.35,.27,.13) * sun + vec3(.16,.12,.05) * glisten * (.75+.25*sin(phase*6.0));
    }
    if (mode >= 3.5) {
        // Match the accretion disk's world-fixed direction while preserving the
        // dark cool atmosphere elsewhere; the sky remains the visual focal point.
        float accretion = pow(max(dot(direction,normalize(vec3(.40,.44,-.80))),0.0),10.0);
        vec3 haze = mix(vec3(.052,.045,.091),vec3(.23,.18,.28),mist);
        return haze + vec3(.32,.17,.060)*accretion*(.65+.35*mist);
    }
    // Abyss: deep teal volume and soft submerged-looking, descending light.
    // Camera submersion itself is excluded in Java: this is an air atmosphere.
    float swell = .5+.5*sin(worldHeight*.012 - phase*3.0 + mist*2.0);
    vec3 haze = mix(vec3(.025,.075,.15), vec3(.10,.35,.43), mist);
    return haze + vec3(.035,.13,.16) * swell * mist;
}

void main() {
    float depth = texture(SceneDepth, texCoord).r;
    float density = clamp(ColorModulator.y,0.0,1.0);
    // Empty sky, cleared/invalid near depth and a disabled effect are exact identity.
    if (depth <= 0.0 || depth >= .999999 || density <= 0.0) discard;
    vec4 viewH = mix(nearPlane,farPlane,depth);
    if (abs(viewH.w) < 0.000001) discard;
    vec3 viewPosition = viewH.xyz/viewH.w;
    if (any(isnan(viewPosition)) || any(isinf(viewPosition))) discard;
    float rayDistance = length(viewPosition);
    if (rayDistance <= 8.0 || rayDistance > 32768.0) discard;
    vec3 worldRay = mat3(TextureMat) * viewPosition;
    vec3 direction = normalize(worldRay);
    float configuredDistance = clamp(ColorModulator.z,32.0,512.0);
    float phase = ColorModulator.w;
    vec3 drift = vec3(sin(phase*2.0), -sin(phase*3.0), cos(phase*2.0))*.22;
    float mist = 0.0;
    // Four fixed integration samples: bounded cost, stable pixels, no temporal
    // dither/undersampled raymarch trails, and no huge stacks of translucent meshes.
    for (int sampleIndex=0; sampleIndex<4; sampleIndex++) {
        float along = (float(sampleIndex)+.5)*.25;
        vec3 point = ModelOffset + worldRay*along;
        float vapor = volumeNoise(point/64.0 + drift);
        float valley = clamp(exp((64.0-point.y)*.006),.50,1.40);
        mist += (.40 + vapor*.60)*valley*.25;
    }
    mist = clamp(mist,0.0,1.0);
    float opticalDepth = max(0.0,rayDistance-8.0)/configuredDistance*density*1.85;
    float foreground = smoothstep(8.0,24.0,rayDistance);
    float alpha = min(.94,(1.0-exp(-opticalDepth*(.72+mist*.62)))*foreground);
    vec3 color = material(ColorModulator.x,direction,mist,phase,ModelOffset.y+worldRay.y);
    fragColor = vec4(clamp(color,0.0,1.0),alpha);
}
