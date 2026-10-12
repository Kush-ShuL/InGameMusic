#version 150
uniform sampler2D Background;
layout(std140) uniform GlassData {
    vec4 Screen;  // framebuffer width, height
    vec4 Rect;    // panel x, y (top-left), width, height in physical pixels
    vec4 Surface; // corner radius, refractive index, physical UI scale, neutral mask opacity
    vec4 Reserved;
};
in vec2 texCoord;
out vec4 fragColor;

// A plano-convex solid: a smooth crown across the ENTIRE panel, rounded shoulders,
// and a planar back. Units are physical pixels. There is no arbitrary rim UV offset.
float distanceToEdge(vec2 point) {
    vec2 q = abs(point) - Rect.zw * 0.5 + vec2(Surface.x);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - Surface.x;
}
vec3 geometry(vec2 point) {
    vec2 halfSize = Rect.zw * 0.5;
    float shortSide = min(halfSize.x, halfSize.y);
    // Keep comparable curvature at HUD and main-window sizes. A fixed height
    // cap made the large panes nearly flat while the small HUD remained a lens.
    float shoulderWidth = min(max(24.0 * Surface.z, shortSide * 0.22), shortSide * 0.65);
    float shoulderHeight = shoulderWidth * 0.16;
    float crownHeight = shortSide * 0.10;
    vec2 q = point / halfSize;
    vec2 body = max(vec2(0.0), 1.0 - q * q);
    // Blend the two shoulder profiles instead of differentiating nearest-edge
    // distance. An SDF's interior medial axes caused diagonal normal seams when
    // the shoulder was wider than the corner radius.
    vec2 t = clamp((halfSize - abs(point)) / shoulderWidth, 0.0, 1.0);
    // p(t) = 2t - 2t^3 + t^4. At the join p=1, p'=0 AND p''=0:
    // both the normal and its slope join the crown continuously (C2 height).
    vec2 shoulder = t * (2.0 + t * t * (t - 2.0));
    vec2 shoulderSlope = 2.0 * (1.0 - t) * (1.0 - t) * (1.0 + 2.0 * t);
    float height = 2.0 * Surface.z + shoulderHeight * shoulder.x * shoulder.y + crownHeight * body.x * body.y;
    vec2 slope = -2.0 * crownHeight * q / halfSize * body.yx;
    slope -= sign(point) * shoulderHeight / shoulderWidth * shoulderSlope * shoulder.yx;
    return vec3(slope, height);
}
// Unpolarized dielectric Fresnel reflectance, including total internal reflection.
// Relative index eta is n_transmitted / n_incident.
float fresnel(float cosine, float eta) {
    float sin2 = max(0.0, 1.0 - cosine * cosine) / (eta * eta);
    if (sin2 >= 1.0) return 1.0;
    float transmitted = sqrt(1.0 - sin2);
    float rs = (cosine - eta * transmitted) / max(cosine + eta * transmitted, 0.00001);
    float rp = (eta * cosine - transmitted) / max(eta * cosine + transmitted, 0.00001);
    return 0.5 * (rs * rs + rp * rp);
}
vec3 sampleScene(vec2 pixel) {
    vec2 uv = pixel / Screen.xy;
    uv.y = 1.0 - uv.y;
    vec2 inset = 0.5 / Screen.xy;
    return texture(Background, clamp(uv, inset, 1.0 - inset)).rgb;
}
// Trace entry -> travel through the glass thickness -> exit -> virtual scene plane.
// Scene depth and off-screen radiance are unavailable: the screen is a planar proxy.
vec3 traceChannel(vec2 pixel, vec3 normal, float thickness, float index) {
    vec3 inside = refract(vec3(0.0, 0.0, -1.0), normal, 1.0 / index);
    vec2 exitPoint = pixel + inside.xy * (thickness / max(-inside.z, 0.0001));
    vec3 outgoing = refract(inside, vec3(0.0, 0.0, 1.0), index);
    float entryReflection = fresnel(normal.z, index);
    float exitReflection = fresnel(-inside.z, 1.0 / index);
    float transmission = (1.0 - entryReflection) * (1.0 - exitReflection);
    vec2 scenePoint = exitPoint;
    if (outgoing.z < -0.0001) scenePoint += outgoing.xy / -outgoing.z * (24.0 * Surface.z);
    else transmission = 0.0;
    return vec3(scenePoint, transmission);
}
vec3 linearColor(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
void main() {
    vec2 local = vec2(texCoord.x, 1.0 - texCoord.y) * Rect.zw;
    vec2 point = local - Rect.zw * 0.5;
    float d = distanceToEdge(point);
    float alpha = 1.0 - smoothstep(-max(fwidth(d), 0.7), 0.0, d);
    if (alpha <= 0.0) { fragColor = vec4(0.0); return; }
    vec3 shape = geometry(point);
    vec3 normal = normalize(vec3(-shape.xy, 1.0));
    vec2 pixel = Rect.xy + local;
    // Cauchy dispersion fitted to the selected d-line index and Abbe number 32.
    // Wavelengths: C (red), d (green), F (blue), in micrometers.
    vec3 wavelength = vec3(0.6563, 0.5876, 0.4861);
    float b = (Surface.y - 1.0) / (32.0 * (1.0 / (0.4861 * 0.4861) - 1.0 / (0.6563 * 0.6563)));
    vec3 indices = Surface.y + b * (1.0 / (wavelength * wavelength) - 1.0 / (0.5876 * 0.5876));
    vec3 red = traceChannel(pixel, normal, shape.z, indices.r);
    vec3 green = traceChannel(pixel, normal, shape.z, indices.g);
    vec3 blue = traceChannel(pixel, normal, shape.z, indices.b);
    vec3 transmitted = vec3(sampleScene(red.xy).r, sampleScene(green.xy).g, sampleScene(blue.xy).b);
    vec3 reflectedRay = reflect(vec3(0.0, 0.0, -1.0), normal);
    // Screen-space reflected radiance proxy, taken from the same permitted blur layer.
    vec3 reflected = sampleScene(pixel + reflectedRay.xy * (48.0 * Surface.z));
    vec3 transmittance = vec3(red.z, green.z, blue.z);
    // All reflected and transmitted radiance comes from the scene itself.
    vec3 color = pow(mix(linearColor(reflected), linearColor(transmitted), transmittance), vec3(1.0 / 2.2));
    color *= 1.0 - clamp(Surface.w, 0.0, 1.0);
    fragColor = vec4(clamp(color, 0.0, 1.0), alpha);
}
