#version 150
uniform sampler2D Background;
layout(std140) uniform GlassData {
    vec4 Step; // one native texel along the filter axis, radius in physical pixels
    vec4 Rect;
    vec4 Surface;
    vec4 Reserved;
};
in vec2 texCoord;
out vec4 fragColor;
vec3 sampleClamped(vec2 uv) {
    vec2 inset = 0.5 / vec2(textureSize(Background, 0));
    return texture(Background, clamp(uv, inset, 1.0 - inset)).rgb;
}
void main() {
    if (Step.z < 0.01) { fragColor = vec4(sampleClamped(texCoord), 1.0); return; }
    float sigma = max(Step.z / 3.0, 0.25);
    int radius = int(ceil(Step.z));
    vec3 color = sampleClamped(texCoord);
    float total = 1.0;
    // Every adjacent texel contributes. Combine each pair using hardware bilinear
    // interpolation; unlike stretched 5-tap kernels this cannot create ghost stripes.
    for (int i = 1; i <= radius; i += 2) {
        float a = float(i), b = float(i + 1);
        float wa = exp(-0.5 * a * a / (sigma * sigma));
        float wb = i + 1 <= radius ? exp(-0.5 * b * b / (sigma * sigma)) : 0.0;
        float weight = wa + wb;
        float offset = (a * wa + b * wb) / max(weight, 0.000001);
        color += (sampleClamped(texCoord + Step.xy * offset) + sampleClamped(texCoord - Step.xy * offset)) * weight;
        total += 2.0 * weight;
    }
    fragColor = vec4(color / total, 1.0);
}
