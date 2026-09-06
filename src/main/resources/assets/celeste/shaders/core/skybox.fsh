#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 worldDirection;
out vec4 fragColor;

// ColorModulator = mode, brightness, bounded animation phase, star amount.
// ModelOffset.x is rain coverage. Every temporal expression loops at phase 2pi.
float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    return vec3(hash31(p), hash31(p + 19.17), hash31(p + 43.71));
}

float skyNoise3(vec3 p) {
    vec3 cell = floor(p), f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(mix(hash31(cell), hash31(cell + vec3(1,0,0)), f.x),
                   mix(hash31(cell + vec3(0,1,0)), hash31(cell + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash31(cell + vec3(0,0,1)), hash31(cell + vec3(1,0,1)), f.x),
                   mix(hash31(cell + vec3(0,1,1)), hash31(cell + vec3(1,1,1)), f.x), f.y), f.z);
}

float cloudNoise(vec3 p) {
    // Three broad octaves: no expensive ray marching and no narrow wire/rib detail.
    float value = skyNoise3(p) * 0.57;
    value += skyNoise3(p * 2.03 + 17.1) * 0.29;
    value += skyNoise3(p * 4.11 + 31.7) * 0.14;
    return value;
}

float stars(vec3 direction, float phase) {
    vec3 p = direction * 145.0;
    vec3 cell = floor(p), local = fract(p) - 0.5;
    vec3 jitter = (hash33(cell + 7.3) - 0.5) * 0.46;
    float seed = hash31(cell + 67.2);
    float distanceToStar = length(local - jitter);
    float footprint = clamp(length(fwidth(p)), 0.015, 0.10);
    float radius = mix(0.045, 0.082, hash31(cell + 5.8));
    float core = 1.0 - smoothstep(radius, radius + footprint, distanceToStar);
    // Each glow fits wholly in its 3D cell, so no cell/spherical-UV seams appear.
    float halo = (1.0 - smoothstep(radius, 0.21, distanceToStar)) * 0.10;
    float twinkle = 0.78 + 0.22 * sin(phase * 3.0 + seed * 50.0);
    return (core + halo) * smoothstep(0.965, 0.985, seed) * twinkle;
}

vec3 aurora(vec3 d, float phase, out float cloudCover) {
    vec3 drift = vec3(sin(phase), cos(phase), sin(phase * 2.0)) * 0.32;
    float clouds = cloudNoise(d * 3.5 + drift + vec3(8,1,4));
    float fold = cloudNoise(d * 5.2 - drift * 0.6 + vec3(1,7,3));
    float lower = d.y - (0.32 + 0.13 * sin(d.x * 3.7 + sin(phase) * 0.42)
                                      + 0.07 * cos(d.z * 4.1 + cos(phase) * 0.36));
    float upper = d.y - (0.60 + 0.12 * sin(d.z * 3.4 - sin(phase) * 0.35));
    float ribbon = exp(-lower * lower / 0.021) * (0.40 + clouds * 0.70);
    float highRibbon = exp(-upper * upper / 0.035) * (0.25 + fold * 0.65);
    vec3 zenith = mix(vec3(0.016,0.027,0.092), vec3(0.042,0.026,0.115), max(d.y,0.0));
    vec3 first = mix(vec3(0.055,0.55,0.48), vec3(0.18,0.31,0.72), smoothstep(0.28,0.78,fold));
    vec3 second = mix(vec3(0.16,0.22,0.56), vec3(0.43,0.14,0.50), smoothstep(-0.8,0.8,d.x));
    cloudCover = clamp(ribbon * 0.50 + highRibbon * 0.35, 0.0, 0.88);
    return zenith + first * ribbon * 0.88 + second * highRibbon * 0.64
        + vec3(0.055,0.08,0.16) * clouds * clouds;
}

vec3 nebula(vec3 d, float phase, out float cloudCover) {
    vec3 drift = vec3(sin(phase), cos(phase), sin(phase * 2.0)) * 0.22;
    float broad = cloudNoise(d * 3.1 + drift + vec3(3,9,2));
    float detail = cloudNoise(d * 6.2 - drift + vec3(7,2,11));
    float plane = dot(d, normalize(vec3(0.32,0.80,-0.29)));
    float belt = exp(-plane * plane * 3.6);
    float mist = smoothstep(0.24,0.72,broad) * belt;
    float luminous = smoothstep(0.37,0.74,broad * 0.66 + detail * 0.34);
    vec3 velvet = mix(vec3(0.017,0.021,0.068), vec3(0.044,0.029,0.12), max(d.y,0.0));
    vec3 pigment = mix(vec3(0.10,0.27,0.60), vec3(0.46,0.16,0.49), smoothstep(0.25,0.77,detail));
    cloudCover = mist * 0.63;
    return velvet + pigment * mist * 1.02 + vec3(0.20,0.24,0.45) * luminous * belt * 0.58;
}

vec3 nebulaCelestial(vec3 d, float phase, vec3 background, vec3 cloudSky, float cover) {
    // A real sphere and tilted annulus in world-direction space: neither camera
    // position nor screen coordinates enter the geometry. Angular radii ~6/12deg.
    vec3 center = normalize(vec3(-0.30,0.46,-0.84));
    vec3 ringNormal = normalize(vec3(0.42,0.83,-0.36));
    vec3 light = normalize(vec3(-0.25,0.30,0.95));
    float radius = 0.104528;
    float facing = dot(d,center);
    float impact = length(cross(d,center));
    float rayAA = clamp(length(fwidth(d)) * 0.65,0.00008,0.006);
    float sphereMask = (1.0-smoothstep(radius-rayAA,radius+rayAA,impact))
        * step(0.0,facing);
    float sphereT = facing-sqrt(max(radius*radius-impact*impact,0.0));
    vec3 normal = normalize(d*sphereT-center);

    float denominator = dot(d,ringNormal);
    float safeDenominator = (denominator < 0.0 ? -1.0 : 1.0) * max(abs(denominator),0.02);
    float ringT = dot(center,ringNormal)/safeDenominator;
    vec3 ringPoint = d*ringT-center;
    float ringRadius = length(ringPoint);
    float ringAA = clamp(length(fwidth(ringPoint)) * 0.65,0.0001,0.006);
    float ringMask = smoothstep(0.134-ringAA,0.134+ringAA,ringRadius)
        * (1.0-smoothstep(0.214-ringAA,0.214+ringAA,ringRadius))
        * step(0.02,denominator);

    // Broad gas bands and a soft terminator supply volume without contour lines.
    vec3 drift = vec3(sin(phase),cos(phase),sin(phase*2.0)) * 0.10;
    float gas = cloudNoise(normal*3.4+drift+vec3(4,12,7));
    float latitude = dot(normal,ringNormal)+(gas-0.5)*0.18;
    float band = exp(-pow((latitude+0.10)/0.28,2.0));
    vec3 surface = mix(vec3(0.35,0.39,0.62),vec3(0.70,0.59,0.76),gas);
    surface = mix(surface,vec3(0.34,0.24,0.48),band*0.48);
    float diffuse = pow(max(dot(normal,light),0.0),0.82);
    vec3 sphereColor = surface*(0.12+diffuse*0.96);
    float glancing = pow(1.0-clamp(dot(normal,-d),0.0,1.0),2.0);
    sphereColor += vec3(0.11,0.16,0.28)*glancing*(0.20+diffuse*0.42);

    float radial = clamp((ringRadius-0.134)/0.080,0.0,1.0);
    vec3 ringColor = mix(vec3(0.46,0.40,0.65),vec3(0.77,0.63,0.76),radial);
    ringColor *= 0.78+0.22*sin(radial*3.14159265);
    // The planet also casts a broad, softly filtered shadow into the ring plane.
    float alongLight = dot(ringPoint,light);
    float shadowDistance = length(ringPoint-light*alongLight);
    float ringShadow = mix(1.0,smoothstep(radius*0.84,radius*1.12,shadowDistance),
        1.0-smoothstep(-0.018,0.018,alongLight));
    ringColor *= 0.25+0.75*ringShadow;

    // Cloud radiance lies in front of the celestial body; opaque surfaces still
    // occlude background stars, rather than becoming a transparent flat decal.
    float veil = clamp(cover*1.35,0.0,0.76);
    sphereColor = mix(sphereColor,cloudSky,veil);
    ringColor = mix(ringColor,cloudSky,veil);
    float ringAlpha = ringMask*(0.72-veil*0.12);
    float nearRing = step(ringT,sphereT);
    float halo = exp(-pow(max(impact-radius,0.0)/0.038,2.0))
        * (1.0-sphereMask)*step(0.0,facing)*(1.0-veil);
    vec3 sky = background+vec3(0.055,0.065,0.12)*halo;
    sky = mix(sky,ringColor,ringAlpha*(1.0-nearRing));
    sky = mix(sky,sphereColor,sphereMask);
    return mix(sky,ringColor,ringAlpha*nearRing);
}

vec3 sunset(vec3 d, float phase, out float cloudCover) {
    vec3 drift = vec3(sin(phase), cos(phase), sin(phase * 2.0)) * 0.17;
    float clouds = cloudNoise(d * vec3(4.5,7.0,4.5) + drift + vec3(13,3,5));
    float veil = smoothstep(0.34,0.74,clouds) * smoothstep(0.03,0.30,d.y);
    float altitude = smoothstep(0.02,0.86,max(d.y,0.0));
    vec3 sky = mix(vec3(0.90,0.38,0.23), vec3(0.095,0.075,0.24), altitude);
    vec3 sunDirection = normalize(vec3(0.55,0.23,-0.80));
    float facing = dot(d, sunDirection);
    float radiance = exp(-max(0.0,1.0-facing) * 12.0);
    float disc = smoothstep(0.9980,0.9994,facing);
    vec3 cloudColor = mix(vec3(0.72,0.28,0.27), vec3(0.24,0.14,0.31), altitude);
    sky = mix(sky, cloudColor, veil * 0.58);
    sky += vec3(0.50,0.25,0.10) * radiance * (1.0-veil*0.35);
    sky += vec3(0.65,0.54,0.32) * disc * (1.0-veil*0.66);
    cloudCover = veil;
    return sky;
}

void main() {
    vec3 d = normalize(worldDirection);
    float phase = ColorModulator.z;
    float cover;
    vec3 sky;
    if (ColorModulator.x < 0.5) sky = aurora(d, phase, cover);
    else if (ColorModulator.x < 1.5) sky = nebula(d, phase, cover);
    else sky = sunset(d, phase, cover);
    vec3 cloudSky = sky;
    float starAmount = clamp(ColorModulator.w,0.0,1.0) * (1.0-cover*0.72);
    if (ColorModulator.x > 1.5) starAmount *= 0.15 * smoothstep(0.35,0.85,d.y);
    sky += vec3(0.72,0.82,1.0) * stars(d,phase) * starAmount;
    if (ColorModulator.x >= 0.5 && ColorModulator.x < 1.5)
        sky = nebulaCelestial(d,phase,sky,cloudSky,cover);
    sky = max(sky,vec3(0.0)) * clamp(ColorModulator.y,0.35,1.4);
    sky = sky / (vec3(1.0) + sky * 0.22);
    float rain = clamp(ModelOffset.x,0.0,1.0);
    sky = mix(sky, mix(FogColor.rgb,vec3(dot(sky,vec3(0.2126,0.7152,0.0722))),0.28),rain*0.76);
    // Exact native fog at/below the horizon, including Custom Fog's updated UBO.
    float dome = smoothstep(0.0,0.24,d.y);
    fragColor = vec4(clamp(mix(FogColor.rgb,sky,dome),0.0,1.0),1.0);
}
