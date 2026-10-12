package top.mc_plfd_host.ingamemusic.ui;

import top.mc_plfd_host.ingamemusic.library.MusicState;

/** Validated, immutable live appearance settings. Blur is measured in design pixels. */
public record GlassStyle(boolean enabled, double blur, double refraction, boolean respectBackgroundBlur, double maskOpacity) {
    public static final double DEFAULT_BLUR = 6, DEFAULT_REFRACTION = 1.35;
    public static final double MAX_BLUR = 24, MIN_REFRACTION = 1, MAX_REFRACTION = 2;
    public static final GlassStyle DEFAULT = new GlassStyle(false, DEFAULT_BLUR, DEFAULT_REFRACTION, true);
    public GlassStyle(boolean enabled, double blur, double refraction, boolean respectBackgroundBlur) {
        this(enabled, blur, refraction, respectBackgroundBlur, 0);
    }
    public GlassStyle {
        maskOpacity = Math.clamp(Double.isFinite(maskOpacity) ? maskOpacity : 0, 0, 1);
        blur = Math.clamp(Double.isFinite(blur) ? blur : DEFAULT_BLUR, 0, MAX_BLUR);
        refraction = Math.clamp(Double.isFinite(refraction) ? refraction : DEFAULT_REFRACTION, MIN_REFRACTION, MAX_REFRACTION);
    }
    public static GlassStyle from(MusicState state) {
        return new GlassStyle(state.liquidGlass, state.glassBlur, state.glassRefraction, state.glassRespectBackgroundBlur, state.glassMaskOpacity);
    }
    public void store(MusicState state) {
        state.liquidGlass = enabled; state.glassBlur = blur;
        state.glassRefraction = refraction; state.glassMaskOpacity = maskOpacity; state.glassRespectBackgroundBlur = respectBackgroundBlur;
    }
    public GlassStyle withEnabled(boolean value) { return new GlassStyle(value, blur, refraction, respectBackgroundBlur, maskOpacity); }
    public GlassStyle withBlur(double value) { return new GlassStyle(enabled, value, refraction, respectBackgroundBlur, maskOpacity); }
    public GlassStyle withRefraction(double value) { return new GlassStyle(enabled, blur, value, respectBackgroundBlur, maskOpacity); }
    public GlassStyle withRespectBackgroundBlur(boolean value) { return new GlassStyle(enabled, blur, refraction, value, maskOpacity); }
    public GlassStyle withMaskOpacity(double value) { return new GlassStyle(enabled, blur, refraction, respectBackgroundBlur, value); }
}
