#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

out vec3 worldDirection;

void main() {
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec2 clip = corner * 2.0 - 1.0;
    vec4 view = inverse(ProjMat) * vec4(clip, 1.0, 1.0);
    // Only camera rotation affects celestial direction. Translation never does.
    worldDirection = transpose(mat3(ModelViewMat)) * view.xyz;
    gl_Position = vec4(clip, 1.0, 1.0);
}
