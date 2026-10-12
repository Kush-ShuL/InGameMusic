package top.mc_plfd_host.ingamemusic.ui;

import top.mc_plfd_host.ingamemusic.MusicController;
import top.mc_plfd_host.ingamemusic.audio.AudioPlayer;
import static top.mc_plfd_host.ingamemusic.ui.Canvas.*;
import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;

public final class HudView {
    public static int width(boolean compact) { return compact ? 300 : 344; }
    public static int height(boolean compact) { return compact ? 58 : 100; }
    public static Canvas.Scene scene(MusicController app, CoverCache covers, boolean compact) {
        return scene(app, covers, compact, null, -1, -1, false);
    }
    public static Canvas.Scene scene(MusicController app, CoverCache covers, boolean compact, UiMotion motion, double mouseX, double mouseY, boolean pressed) {
        int w = width(compact), h = height(compact);
        try (Canvas c = new Canvas(w, h)) {
            c.round(2, 4, w - 4, h - 4, 14, 0x20000000);
            c.glass(2, 1, w - 4, h - 6, 17, PANEL);
            if (motion != null) {
                int[][] controls = compact ? new int[][]{{208, 10, 33, 36}, {241, 10, 28, 36}, {269, 10, 28, 36}}
                        : new int[][]{{73, 55, 31, 28}, {104, 55, 39, 28}, {143, 55, 37, 28}, {280, 10, 27, 35}, {307, 10, 28, 35}};
                for (int i = 0; i < controls.length; i++) {
                    int[] r = controls[i]; boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
                    double over = motion.value("hud:" + compact + ":" + i, hover);
                    double down = motion.value("hud-press:" + compact + ":" + i, hover && pressed);
                    c.round(r[0], r[1], r[2], r[3], 8, UiMotion.opacity(0x28187b70, Math.max(over * .55, down)));
                }
            }
            var track = app.player.track();
            boolean playing = app.player.status() == AudioPlayer.Status.PLAYING;
            String title = track == null ? tr("hud.empty") : track.title();
            String artist = track == null ? tr("hud.hint") : track.displayArtist();
            double shownPosition = app.displayPosition(System.nanoTime());
            double fraction = track == null || track.duration() <= 0 ? 0 : Math.clamp(shownPosition / track.duration(), 0, 1);
            if (compact) {
                c.cover(track == null ? null : covers.get(track, app.state.generatedCovers), 10, 10, 36, track == null ? 174 : track.album().hashCode());
                c.fit(title, 56, 26, 14, TEXT, 151); c.fit(artist, 56, 42, 11, MUTED, 151);
                c.icon(playing ? "pause" : "play", 216, 17, 22, ACCENT);
                c.icon("expand", 246, 20, 18, MUTED); c.icon("grid", 274, 20, 17, MUTED);
                c.round(10, 51, 280, 2, 1, CARD); c.round(10, 51, 280 * fraction, 2, 1, ACCENT);
            } else {
                c.cover(track == null ? null : covers.get(track, app.state.generatedCovers), 14, 15, 54, track == null ? 174 : track.album().hashCode());
                c.fit(title, 80, 31, 14, TEXT, 199); c.fit(artist, 80, 51, 11, MUTED, 220);
                c.icon("compact", 285, 16, 17, MUTED); c.icon("grid", 311, 16, 17, MUTED);
                c.icon("prev", 79, 61, 19, TEXT); c.icon(playing ? "pause" : "play", 115, 61, 19, ACCENT); c.icon("next", 151, 61, 19, TEXT);
                c.text(time(shownPosition), 195, 75, 10, MUTED);
                c.text(track == null || track.duration() <= 0 ? tr("value.unknown") : time(track.duration()), 273, 75, 10, MUTED);
                c.round(14, 86, 314, 3, 2, CARD); c.round(14, 86, 314 * fraction, 3, 2, ACCENT);
                if (playing) {
                    long now = System.nanoTime(); float amplitude = app.player.level();
                    for (int i = 0; i < 4; i++) {
                        double level = 3 + amplitude * 16 * (.4 + .6 * Math.abs(Math.sin(now / 200_000_000.0 + i)));
                        c.round(308 + i * 5, 74 - level, 2, level, 1, ACCENT);
                    }
                }
            }
            return c.finish();
        }
    }
}
