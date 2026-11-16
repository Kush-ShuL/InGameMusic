#version 150
uniform sampler2D Background;
layout(std140) uniform GlassData {
    vec4 Screen;
    vec4 Rect;
    vec4 Surface; // corner radius, refractive index, physical UI scale, mask opacity
    vec4 Reserved;
};
in vec2 texCoord;
out vec4 fragColor;

float distanceToEdge(vec2 point) {
    vec2 q = abs(point) - Rect.zw * 0.5 + vec2(Surface.x);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - Surface.x;
}
vec2 edgeNormal(vec2 point) {
    vec2 q = abs(point) - Rect.zw * 0.5 + Surface.x;
    vec2 corner = max(q, vec2(0.0));
    if (dot(corner, corner) > 0.00001) return normalize(corner) * sign(point);
    return q.x > q.y ? vec2(sign(point.x), 0.0) : vec2(0.0, sign(point.y));
}
vec3 sampleScene(vec2 pixel) {
    vec2 uv = pixel / Screen.xy;
    uv.y = 1.0 - uv.y;
    vec2 inset = 0.5 / Screen.xy;
    return texture(Background, clamp(uv, inset, 1.0 - inset)).rgb;
}
void main() {
    vec2 local = vec2(texCoord.x, 1.0 - texCoord.y) * Rect.zw;
    vec2 point = local - Rect.zw * 0.5;
    float d = distanceToEdge(point);
    float aa = max(fwidth(d), 0.7);
    float alpha = 1.0 - smoothstep(-aa, 0.0, d);
    if (alpha <= 0.0) { fragColor = vec4(0.0); return; }

    vec2 pixel = Rect.xy + local;
    float depth = max(-d, 0.0);
    // Only the narrow rounded edge acts as a lens. The broad face is flat,
    // with no magnification, artificial lighting or colored coating.
    float edgeWidth = min(Surface.x * 0.85, 16.0 * Surface.z);
    float edge = clamp(1.0 - depth / edgeWidth, 0.0, 1.0);
    float profile = edge * edge * edge * (edge * (edge * 6.0 - 15.0) + 10.0);
    vec2 normal = edgeNormal(point);
    float bend = (Surface.y - 1.0) * 24.0 * Surface.z * profile;
    vec2 offset = -normal * bend;
    // Small spectral separation fades out with the same edge profile.
    vec3 color = vec3(sampleScene(pixel + offset * 0.98).r,
                      sampleScene(pixel + offset).g,
                      sampleScene(pixel + offset * 1.025).b);

    // A sub-pixel, directional glint defines the outline without a thick bevel.
    float rim = exp(-pow((depth - 0.65 * Surface.z) / max(0.55 * Surface.z, aa * 0.5), 2.0));
    float facing = dot(normal, normalize(vec2(-0.6, -0.8)));
    float sheen = rim * (0.025 + 0.12 * pow(max(facing, 0.0), 2.0) + 0.045 * pow(max(-facing, 0.0), 2.0));
    sheen *= clamp((Surface.y - 1.0) * 2.0, 0.0, 1.0);
    color = mix(color, vec3(1.0), sheen);
    color *= 1.0 - clamp(Surface.w, 0.0, 1.0);
    fragColor = vec4(color, alpha);
}
