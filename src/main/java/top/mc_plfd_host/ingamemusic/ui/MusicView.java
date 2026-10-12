package top.mc_plfd_host.ingamemusic.ui;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import top.mc_plfd_host.ingamemusic.MusicController;
import top.mc_plfd_host.ingamemusic.audio.AudioPlayer;
import top.mc_plfd_host.ingamemusic.library.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.lwjgl.glfw.GLFW.*;
import static top.mc_plfd_host.ingamemusic.ui.Canvas.*;

/** The actual interactive scene can also render headlessly for visual verification. */
public final class MusicView {
    public static final int WIDTH = 1120, HEIGHT = 720;
    public interface Host { void close(); String clipboard(); void clipboard(String text); void editHud(); default String binding(String action) { return action; } }
    private record Hit(int x, int y, int w, int h, Consumer<Integer> action) {
        boolean contains(double px, double py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }
    private record Entry(Path path, boolean directory) {}
    private record Dialog(String title, String description, TextEdit input, Consumer<String> action, boolean danger) {}
    public final MusicController app;
    private final CoverCache covers;
    private final Host host;
    private final List<Hit> hits = new ArrayList<>();
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();
    private final LinkedHashSet<Path> fileSelection = new LinkedHashSet<>();
    private final TextEdit search = new TextEdit(""), address = new TextEdit(System.getProperty("user.home"));
    private TextEdit focus;
    private Dialog dialog;
    private String page = "library", playlist, inspected;
    private String settingsTab = "appearance", focusedSlider = "";
    private int anchor = -1, fileAnchor = -1;
    private record Viewport(int x, int y, int w, int h, int rowHeight) {
        boolean contains(double px, double py) { return px >= x && px < x + w + 18 && py >= y && py < y + h; }
    }
    private static final Viewport LIBRARY = new Viewport(236, 208, 572, 378, 54);
    private static final Viewport SIDEBAR = new Viewport(25, 296, 164, 250, 40);
    private static final Viewport FOLDERS = new Viewport(236, 188, 837, 396, 66);
    private static final Viewport BROWSER = new Viewport(236, 216, 837, 322, 46);
    private static final Viewport CHOOSER = new Viewport(365, 220, 390, 240, 48);
    private final Map<String, SmoothScroll> scrolling = new HashMap<>();
    private final Map<String, Viewport> viewports = new LinkedHashMap<>();
    private Viewport hitClip;
    private long lastFrame;
    private final UiMotion motion = new UiMotion();
    private Hit pressedHit;
    private boolean pointerDown;
    private long pressUntil, rippleStart;
    private double pressX, pressY;
    private boolean overlayLayer;
    private double mouseX = -1, mouseY = -1;
    private long lastClick;
    private String lastPath = "";
    private boolean foldersOnly;
    private volatile List<Entry> files = List.of();
    private volatile String browserError = "";
    private volatile Path directory = Path.of(System.getProperty("user.home"));
    private final AtomicInteger listingVersion = new AtomicInteger();
    private boolean choosePlaylist;
    private String dragging = "";
    private Scrollbar dragBar;
    private double thumbGrab;
    private MusicState cachedState;
    private String cachedQuery, cachedPlaylist;
    private List<Track> cachedTracks = List.of();
    private Map<String, Track> trackIndex = Map.of();
    public boolean hasTextFocus() { return focus != null; }
    public TextEdit.Geometry focusedInput() { return focus == null ? null : focus.geometry(); }
    public MusicView(MusicController app, CoverCache covers, Host host) { this.app = app; this.covers = covers; this.host = host; }
    public BufferedImage render(double scale, double mouseX, double mouseY) { return scene(mouseX, mouseY).rasterize(scale); }
    public Canvas.Scene scene(double mouseX, double mouseY) {
        return scene(mouseX, mouseY, System.nanoTime());
    }
    public Canvas.Scene scene(double mouseX, double mouseY, long frameTime) {
        double elapsed = lastFrame == 0 ? 0 : Math.max(0, (frameTime - lastFrame) / 1_000_000_000.0);
        lastFrame = frameTime;
        motion.begin(frameTime);
        scrolling.values().forEach(state -> state.advance(elapsed));
        this.mouseX = mouseX; this.mouseY = mouseY; hits.clear(); viewports.clear(); hitClip = null; overlayLayer = false;
        try (Canvas c = new Canvas(WIDTH, HEIGHT, app.glassStyle.enabled())) {
            region(c, 20, 20, 186, 576, 0xffedf2f6);
            region(c, 220, 20, 880, 82, BG);
            sidebar(c);
            if (page.equals("browser")) browser(c);
            else if (page.equals("folders")) folders(c);
            else if (page.equals("settings")) settings(c);
            else library(c);
            player(c);
            String notice = app.player.status() == AudioPlayer.Status.ERROR ? tr("error.playback", app.player.error()) : app.notice;
            c.fit(notice, 36, 625, 11, app.player.status() == AudioPlayer.Status.ERROR ? DANGER : MUTED, 1048);
            iconButton(c, "close", 1066, 28, 30, host::close);
            if (choosePlaylist) playlistChooser(c);
            if (dialog != null) dialog(c);
            return c.finish();
        }
    }
    /** Independent surfaces leave the game visible through the gutters. */
    private void region(Canvas c, int x, int y, int width, int height, int tint) {
        c.round(x, y + 3, width, height, 18, 0x18040d1c);
        c.surface(x, y, width, height, 18, tint, app.glassStyle);
    }
    private void sidebar(Canvas c) {
        c.glass(32, 35, 36, 36, 12, ACCENT); c.icon("music", 39, 42, 22, BG);
        c.text(tr("brand.name"), 80, 56, 23, TEXT); c.text(tr("brand.subtitle"), 33, 96, 10, MUTED);
        nav(c, "grid", tr("nav.library"), 112, page.equals("library") && playlist == null, () -> switchLibrary(null));
        nav(c, "folder", tr("nav.folders"), 156, page.equals("folders"), () -> switchPage("folders"));
        nav(c, "settings", tr("nav.settings"), 200, page.equals("settings"), () -> switchPage("settings"));
        c.text(tr("nav.playlists"), 33, 278, 12, MUTED);
        iconButton(c, "plus", 159, 258, 25, this::createPlaylist);
        var lists = app.state.playlists;
        var rows = beginList(c, "sidebar", SIDEBAR, lists.size());
        for (int i = rows.first(); i < rows.end(); i++) {
            var list = lists.get(i); int y = rows.y(i);
            if (list.id.equals(playlist)) c.glass(25, y, 164, 34, 12, SELECTED);
            c.round(25, y, 164, 34, 12, UiMotion.opacity(0x16187b70, motion.value("playlist:" + list.id, hover(25, y, 164, 34))));
            c.icon("list", 35, y + 9, 16, list.id.equals(playlist) ? ACCENT : MUTED);
            c.fit(list.name, 62, y + 22, 13, TEXT, 114);
            hit(25, y, 164, 34, m -> switchLibrary(list.id));
        }
        endList(c, "sidebar", SIDEBAR, lists.size());
        c.glass(27, 551, 161, 37, 13, SELECTED);
        c.text(tr("brand.motto"), 41, 574, 11, ACCENT);
    }
    private void nav(Canvas c, String icon, String label, int y, boolean active, Runnable action) {
        boolean hover = hover(25, y, 164, 40);
        if (active) c.glass(25, y, 164, 40, 14, SELECTED);
        double warmth = motion.value("nav:" + icon, hover);
        c.round(25, y, 164, 40, 14, UiMotion.opacity(0x15187b70, warmth));
        double marker = motion.value("nav-active:" + icon, active);
        c.round(26, y + 11, 3, 18, 1.5, UiMotion.opacity(ACCENT, marker));
        glassFeedback(c, 25, y, 164, 40, 14);
        c.icon(icon, 37, y + 10, 20, active ? ACCENT : MUTED); c.text(label, 68, y + 26, 14, active ? ACCENT : TEXT);
        hit(25, y, 164, 40, m -> action.run());
    }
    private List<Track> visible() {
        MusicState snapshot = app.state;
        if (cachedState == snapshot && Objects.equals(cachedQuery, search.value) && Objects.equals(cachedPlaylist, playlist)) return cachedTracks;
        var tracks = snapshot.tracks;
        var index = new HashMap<String, Track>(); tracks.forEach(t -> index.put(t.path(), t)); trackIndex = index;
        if (playlist != null) {
            var p = app.state.playlists.stream().filter(s -> s.id.equals(playlist)).findFirst().orElse(null);
            if (p == null) { playlist = null; }
            else {
                var byPath = new HashMap<String, Track>(); tracks.forEach(t -> byPath.put(t.path(), t));
                tracks = p.tracks.stream().map(byPath::get).filter(Objects::nonNull).toList();
            }
        }
        cachedState = snapshot; cachedQuery = search.value; cachedPlaylist = playlist;
        cachedTracks = tracks.stream().filter(t -> t.matches(search.value)).toList();
        return cachedTracks;
    }
    private void library(Canvas c) {
        region(c, 220, 114, 602, 482, BG);
        List<Track> tracks = visible();
        String title = playlist == null ? tr("library.title") : app.state.playlists.stream().filter(p -> p.id.equals(playlist)).map(p -> p.name).findFirst().orElse(tr("playlist.generic"));
        c.fit(title, 236, 65, 27, TEXT, 310);
        c.text(tr("library.count", tracks.size()), 236, 88, 12, MUTED);
        search.paint(c, 570, 35, 472, 38, tr("search.placeholder"), focus == search);
        hit(570, 35, 472, 38, m -> focus = search);
        if (selection.isEmpty()) {
            button(c, tr("action.play_all"), 236, 126, 92, 36, true, () -> { if (!tracks.isEmpty()) app.play(tracks.getFirst(), tracks); });
            button(c, tr("action.import_files"), 336, 126, 104, 36, false, () -> openBrowser(false));
            button(c, tr("action.add_folder"), 448, 126, 104, 36, false, () -> openBrowser(true));
            if (playlist != null) {
                button(c, tr("action.rename_playlist"), 560, 126, 110, 36, false, this::renamePlaylist);
                button(c, tr("action.delete_playlist"), 678, 126, 130, 36, false, this::deletePlaylist);
            }
            c.text(tr("library.double_click"), 236, 179, 11, MUTED);
        } else {
            button(c, tr("action.add_to_playlist"), 236, 126, 104, 36, true, () -> { choosePlaylist = true; scrollState("chooser").reset(); focus = null; });
            button(c, tr("action.rename"), 348, 126, 88, 36, false, this::renameFiles);
            button(c, tr("action.remove"), 444, 126, 72, 36, false, this::removeSelected);
            button(c, tr("action.delete_files"), 524, 126, 100, 36, false, this::deleteFiles);
            button(c, tr("action.clear_selection"), 632, 126, 112, 36, false, selection::clear);
            c.text(tr("selection.count", selection.size()), 236, 179, 11, ACCENT);
        }
        c.text(tr("column.track"), 276, 193, 11, MUTED);
        c.text(tr("column.album"), 566, 193, 11, MUTED); c.text(tr("column.duration"), 755, 193, 11, MUTED);
        c.line(236, 202, 808, 202, 1, BORDER);
        if (tracks.isEmpty()) {
            c.icon("music", 494, 316, 54, ACCENT);
            c.centered(search.value.isEmpty() ? tr("empty.library") : tr("empty.search"), 522, 411, 19, TEXT);
            c.centered(tr("empty.library_hint"), 522, 442, 12, MUTED);
            button(c, tr("action.choose_files"), 447, 466, 150, 38, true, () -> openBrowser(false));
        }
        var rows = beginList(c, "library", LIBRARY, tracks.size());
        for (int i = rows.first(); i < rows.end(); i++) {
            Track t = tracks.get(i); int y = rows.y(i); int index = i;
            boolean selected = selection.contains(t.path()), playing = app.player.track() != null && app.player.track().path().equals(t.path());
            if (selected) c.glass(236, y, 572, 51, 13, SELECTED);
            double rowHover = motion.value("track:" + t.path(), hover(236, y, 572, 51));
            c.round(236, y, 572, 51, 13, UiMotion.opacity(0x11187b70, rowHover));
            c.round(247, y + 19, 13, 13, 3, selected ? ACCENT : TRACK);
            if (selected) c.icon("check", 247, y + 19, 13, BG);
            c.cover(covers.get(t, app.state.generatedCovers), 275, y + 7, 36, t.album().hashCode());
            c.fit(t.title(), 324, y + 23, 14, playing ? ACCENT : TEXT, 220);
            c.fit(t.displayArtist(), 324, y + 42, 11, MUTED, 220);
            c.fit(t.displayAlbum(), 566, y + 31, 12, MUTED, 164);
            c.text(t.duration() > 0 ? time(t.duration()) : tr("value.unknown"), 755, y + 31, 12, MUTED);
            hit(236, y, 572, 51, mods -> selectTrack(tracks, index, mods));
        }
        endList(c, "library", LIBRARY, tracks.size());
        details(c);
    }
    private void details(Canvas c) {
        Track t = inspected == null ? app.player.track() : trackIndex.get(inspected);
        region(c, 834, 114, 266, 482, PANEL);
        c.text(t == null ? tr("details.empty_title") : tr("details.title"), 853, 140, 12, MUTED);
        c.cover(t == null ? null : covers.get(t, app.state.generatedCovers), 853, 157, 220, t == null ? 174 : t.album().hashCode());
        c.fit(t == null ? tr("details.empty_track") : t.title(), 853, 411, 18, TEXT, 220);
        c.fit(t == null ? tr("details.empty_artist") : t.displayArtist(), 853, 436, 12, MUTED, 220);
        c.line(853, 452, 1073, 452, 1, BORDER);
        c.text(tr("column.composer"), 853, 478, 11, MUTED);
        c.fit(t == null ? tr("value.unknown") : t.displayComposer(), 925, 478, 12, TEXT, 148);
        c.text(tr("column.album"), 853, 513, 11, MUTED);
        c.fit(t == null ? tr("value.unknown") : t.displayAlbum(), 925, 513, 12, TEXT, 148);
        c.fit(t == null ? tr("details.client_only") : t.file().getFileName().toString(), 853, 557, 10, MUTED, 220);
    }
    private void selectTrack(List<Track> tracks, int index, int mods) {
        focus = null; Track t = tracks.get(index); inspected = t.path();
        boolean ctrl = (mods & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0, shift = (mods & GLFW_MOD_SHIFT) != 0;
        if (shift && anchor >= 0) {
            if (!ctrl) selection.clear();
            for (int i = Math.min(anchor, index); i <= Math.min(Math.max(anchor, index), tracks.size() - 1); i++) selection.add(tracks.get(i).path());
        } else if (ctrl) { if (!selection.remove(t.path())) selection.add(t.path()); anchor = index; }
        else { selection.clear(); selection.add(t.path()); anchor = index; }
        long now = System.currentTimeMillis();
        if (!ctrl && !shift && lastPath.equals(t.path()) && now - lastClick < 350) app.play(t, tracks);
        lastClick = now; lastPath = t.path();
    }
    private void player(Canvas c) {
        region(c, 20, 608, WIDTH - 40, 96, PANEL);
        Track t = app.player.track();
        c.cover(t == null ? null : covers.get(t, app.state.generatedCovers), 30, 642, 52, t == null ? 174 : t.album().hashCode());
        c.fit(t == null ? tr("player.empty") : t.title(), 96, 662, 14, TEXT, 221);
        c.fit(t == null ? tr("player.empty_hint") : t.displayArtist(), 96, 684, 11, MUTED, 221);
        iconButton(c, "prev", 375, 641, 32, () -> app.next(true, false));
        c.glass(420, 635, 44, 44, 22, ACCENT);
        double playHover = motion.value("play-hover", hover(420, 635, 44, 44));
        double playPress = motion.value("play-press", pressed(420, 635, 44, 44));
        c.round(420, 635, 44, 44, 22, UiMotion.opacity(app.glassStyle.enabled() ? 0x30ffffff : 0x16092e29, Math.max(playHover * .55, playPress)));
        glassFeedback(c, 420, 635, 44, 44, 22);
        c.icon(isPlaying() ? "pause" : "play", 431, 646 + Math.round(playPress * 2), 22, BG); hit(420, 635, 44, 44, m -> app.toggle());
        iconButton(c, "next", 477, 641, 32, () -> app.next(false, false));
        button(c, app.modeLabel(), 532, 640, 94, 31, false, app::cycleMode);
        double shownPosition = app.displayPosition(lastFrame);
        double fraction = t != null && t.duration() > 0 ? Math.clamp(shownPosition / t.duration(), 0, 1) : 0;
        if (dragging.equals("seek") && t != null && t.duration() > 0) { fraction = Math.clamp((mouseX - 387) / 289, 0, 1); shownPosition = fraction * t.duration(); }
        c.text(time(shownPosition), 346, 698, 10, MUTED);
        c.round(387, 689, 289, 4, 2, TRACK); c.round(387, 689, 289 * fraction, 4, 2, ACCENT);
        double seekHover = motion.value("seek-hover", hover(383, 680, 297, 22) || dragging.equals("seek"));
        double seekFraction = dragging.equals("seek") ? Math.clamp((mouseX - 387) / 289, 0, 1) : fraction;
        c.round(383 + 289 * seekFraction, 687, 8, 8, 4, UiMotion.opacity(ACCENT, seekHover));
        c.text(t == null || t.duration() <= 0 ? tr("value.unknown") : time(t.duration()), 688, 698, 10, MUTED);
        hit(383, 680, 297, 22, m -> { dragging = "seek"; });
        c.icon("volume", 765, 658, 20, MUTED);
        c.round(798, 667, 110, 4, 2, TRACK); c.round(798, 667, 110 * app.player.volume(), 4, 2, ACCENT);
        double volumeHover = motion.value("volume-hover", hover(790, 651, 129, 34) || dragging.equals("volume"));
        c.round(788 + 110 * app.player.volume(), 657, 23, 23, 11, UiMotion.opacity(0x25187b70, volumeHover));
        c.round(794 + 110 * app.player.volume(), 663, 11, 11, 5, ACCENT);
        hit(790, 651, 129, 34, m -> { dragging = "volume"; app.player.volume((mouseX - 798) / 110); });
        c.text(tr("player.volume", (int) (app.player.volume() * 100)), 923, 674, 11, MUTED);
        button(c, tr("action.mini"), 986, 650, 96, 36, false, host::editHud);
        if (app.player.status() == AudioPlayer.Status.LOADING) c.text(tr("player.loading"), 638, 659, 10, ACCENT);
    }
    private boolean isPlaying() { return app.player.status() == AudioPlayer.Status.PLAYING || app.player.status() == AudioPlayer.Status.LOADING; }
    private void folders(Canvas c) {
        region(c, 220, 114, 880, 482, BG);
        c.text(tr("nav.folders"), 236, 65, 27, TEXT);
        c.text(tr("folders.description"), 236, 94, 13, MUTED);
        button(c, tr("action.add_folder_plus"), 236, 122, 128, 37, true, () -> openBrowser(true));
        button(c, tr("action.rescan_all"), 376, 122, 130, 37, false, () -> app.importPaths(app.state.folders.stream().map(Path::of).toList(), false));
        var rows = beginList(c, "folders", FOLDERS, app.state.folders.size());
        for (int i = rows.first(); i < rows.end(); i++) {
            String path = app.state.folders.get(i); int y = rows.y(i);
            c.glass(236, y, 837, 56, 13, PANEL); c.icon("folder", 252, y + 16, 23, ACCENT);
            c.fit(path, 292, y + 34, 13, TEXT, 540);
            button(c, tr("action.scan"), 860, y + 11, 75, 33, false, () -> app.importPaths(List.of(Path.of(path)), false));
            button(c, tr("action.unlink"), 944, y + 11, 112, 33, false, () -> app.edit(tr("action.unlink_folder"), s -> s.folders.remove(path)));
        }
        endList(c, "folders", FOLDERS, app.state.folders.size());
        if (app.state.folders.isEmpty()) c.text(tr("folders.empty"), 236, 278, 15, MUTED);
    }
    private void settings(Canvas c) {
        c.text(tr("settings.title"), 236, 65, 27, TEXT);
        c.text(tr("settings.subtitle"), 236, 88, 12, MUTED);
        String[] tabs = {"appearance", "playback", "voice", "keys"};
        String[] labels = {tr("settings.tab.appearance"), tr("settings.tab.playback"), tr("settings.tab.voice"), tr("settings.tab.keys")};
        for (int i = 0; i < tabs.length; i++) {
            String tab = tabs[i];
            button(c, labels[i], 220 + i * 224, 118, 208, 38, settingsTab.equals(tab), () -> {
                finishAppearanceDrag(); settingsTab = tab; focusedSlider = "";
            });
        }
        switch (settingsTab) {
            case "playback" -> playbackSettings(c);
            case "voice" -> voiceSettings(c);
            case "keys" -> keySettings(c);
            default -> appearanceSettings(c);
        }
    }
    private void appearanceSettings(Canvas c) {
        var style = app.glassStyle;
        region(c, 220, 170, 552, 426, PANEL);
        c.text(tr("settings.material"), 244, 205, 19, TEXT);
        c.fit(tr("settings.material_hint"), 244, 228, 12, MUTED, 504);
        button(c, tr("option.material_flat"), 244, 246, 244, 40, !style.enabled(), () -> changeGlassStyle(app.glassStyle.withEnabled(false)));
        button(c, tr("option.material_glass"), 500, 246, 248, 40, style.enabled(), () -> changeGlassStyle(app.glassStyle.withEnabled(true)));
        appearanceSlider(c, "glass-blur", tr("settings.glass_blur"), tr("settings.glass_blur_hint"), 318,
                style.blur() / GlassStyle.MAX_BLUR, tr("settings.blur_value", String.format(Locale.ROOT, "%.1f", style.blur())), style.enabled());
        appearanceSlider(c, "glass-refraction", tr("settings.glass_refraction"), tr("settings.glass_refraction_hint"), 404,
                (style.refraction() - 1) / (GlassStyle.MAX_REFRACTION - 1), String.format(Locale.ROOT, "%.2f", style.refraction()), style.enabled());
        appearanceSlider(c, "glass-mask", tr("settings.glass_mask"), tr("settings.glass_mask_hint"), 490,
                1 - style.maskOpacity(), tr("settings.percent_value", (int) Math.round((1 - style.maskOpacity()) * 100)), style.enabled());
        c.fit(tr(style.enabled() ? "settings.glass_live" : "settings.glass_disabled"), 244, 584, 12, MUTED, 504);

        region(c, 784, 170, 316, 260, PANEL);
        c.text(tr("settings.preview"), 808, 205, 19, TEXT);
        c.fit(tr("settings.preview_hint"), 808, 231, 12, MUTED, 268);
        c.round(808, 254, 48, 48, 15, SELECTED); c.icon("music", 820, 266, 24, ACCENT);
        c.text(tr("brand.name"), 872, 275, 19, TEXT);
        c.fit(tr("settings.preview_caption"), 872, 297, 12, MUTED, 200);
        button(c, tr("settings.preview_button"), 808, 325, 268, 42, true, () -> {});
        c.fit(tr("settings.preview_note"), 808, 402, 12, MUTED, 268);
        region(c, 784, 442, 316, 154, PANEL);
        c.text(tr("settings.respect_blur"), 808, 478, 15, TEXT);
        if (style.enabled()) glassBlurToggle(c, style.respectBackgroundBlur());
        else { c.round(982, 455, 94, 32, 12, CARD); c.centered(tr(style.respectBackgroundBlur() ? "option.on" : "option.off"), 1029, 476, 12, MUTED); }
        c.fit(tr("settings.respect_blur_hint"), 808, 514, 12, MUTED, 268);
        c.fit(tr("settings.respect_blur_hint_off"), 808, 537, 12, MUTED, 268);
        button(c, tr("action.reset_appearance"), 808, 554, 268, 30, false,
                () -> changeGlassStyle(new GlassStyle(app.glassStyle.enabled(), GlassStyle.DEFAULT_BLUR, GlassStyle.DEFAULT_REFRACTION, true)));
    }
    private void appearanceSlider(Canvas c, String id, String label, String hint, int baseline, double fraction, String value, boolean enabled) {
        c.text(label, 244, baseline, 15, enabled ? TEXT : MUTED);
        c.fit(hint, 244, baseline + 23, 12, MUTED, 504);
        c.round(244, baseline + 46, 410, 5, 2.5, TRACK);
        c.round(244, baseline + 46, 410 * fraction, 5, 2.5, enabled ? ACCENT : MUTED);
        double over = motion.value(id + ":hover", enabled && (hover(236, baseline + 33, 426, 31) || dragging.equals(id)));
        c.round(233 + 410 * fraction, baseline + 37, 23, 23, 11.5, UiMotion.opacity(0x25187b70, over));
        c.round(240 + 410 * fraction, baseline + 42, 12, 12, 6, enabled ? ACCENT : MUTED);
        c.round(681, baseline + 32, 67, 32, 10, CARD);
        c.centered(value, 714, baseline + 53, 12, enabled ? TEXT : MUTED);
        if (enabled) hit(236, baseline + 33, 426, 31, m -> {
            dragging = id; focusedSlider = id; focus = null; updateAppearanceSlider(mouseX);
        });
    }
    private void updateAppearanceSlider(double x) {
        double fraction = Math.clamp((x - 244) / 410, 0, 1);
        if (dragging.equals("glass-blur")) app.glassStyle = app.glassStyle.withBlur(Math.round(fraction * GlassStyle.MAX_BLUR * 2) / 2.0);
        if (dragging.equals("glass-mask")) app.glassStyle = app.glassStyle.withMaskOpacity(1 - Math.round(fraction * 100) / 100.0);
        if (dragging.equals("glass-refraction")) app.glassStyle = app.glassStyle.withRefraction(1 + Math.round(fraction * 100) / 100.0);
    }
    private void finishAppearanceDrag() {
        if (dragging.startsWith("glass-")) { app.persistGlassStyle(); dragging = ""; }
    }
    private void changeGlassStyle(GlassStyle style) {
        finishAppearanceDrag(); focusedSlider = "";
        app.glassStyle = style; app.persistGlassStyle();
    }
    private void playbackSettings(Canvas c) {
        region(c, 220, 170, 430, 192, PANEL);
        c.text(tr("settings.hud"), 244, 207, 19, TEXT);
        c.fit(tr("settings.hud_description"), 244, 237, 12, MUTED, 382);
        button(c, app.state.hud ? tr("option.on") : tr("option.off"), 244, 290, 146, 40, app.state.hud, () -> app.edit(tr("action.hud"), state -> state.hud = !state.hud));
        button(c, tr("action.position"), 404, 290, 222, 40, false, host::editHud);
        region(c, 662, 170, 438, 192, PANEL);
        c.text(tr("settings.hud_style"), 686, 207, 19, TEXT);
        c.fit(tr("settings.hud_style_hint"), 686, 237, 12, MUTED, 390);
        button(c, tr("option.hud_full"), 686, 290, 188, 40, !app.state.hudCompact, () -> app.edit(tr("action.hud_style"), state -> state.hudCompact = false));
        button(c, tr("option.hud_compact"), 886, 290, 190, 40, app.state.hudCompact, () -> app.edit(tr("action.hud_style"), state -> state.hudCompact = true));
        region(c, 220, 374, 880, 134, PANEL);
        c.text(tr("settings.generated_covers"), 244, 411, 19, TEXT);
        c.fit(tr("settings.generated_covers_hint"), 244, 442, 12, MUTED, 650);
        c.fit(tr("settings.covers_note"), 244, 480, 12, MUTED, 780);
        button(c, app.state.generatedCovers ? tr("option.on") : tr("option.off"), 940, 401, 136, 40, app.state.generatedCovers,
                () -> app.edit(tr("action.generated_covers"), state -> state.generatedCovers = !state.generatedCovers));
        region(c, 220, 520, 880, 76, PANEL);
        c.text(tr("settings.playback_note"), 244, 565, 13, MUTED);
    }
    private void voiceSettings(Canvas c) {
        region(c, 220, 170, 880, 310, PANEL);
        c.text(tr("settings.svc_redirect"), 244, 207, 20, TEXT);
        button(c, app.voiceRedirect.enabled() ? tr("option.on") : tr("option.off"), 940, 187, 136, 40,
                app.voiceRedirect.enabled(), app::toggleVoiceRedirect);
        c.fit(tr("settings.svc_hint"), 244, 242, 13, MUTED, 790);
        c.fit(tr("settings.svc_mute_hint"), 244, 271, 12, MUTED, 790);
        c.round(244, 292, 832, 40, 12, CARD);
        c.fit(tr(app.voiceRedirect.statusKey()), 260, 318, 13, TEXT, 800);
        c.text(tr("settings.svc_gain"), 244, 400, 14, TEXT);
        double gain = app.voiceRedirect.gain();
        double over = motion.value("svc-gain-hover", hover(400, 377, 390, 34) || dragging.equals("svc-gain"));
        c.round(410, 393, 370, 5, 2.5, TRACK); c.round(410, 393, 185 * gain, 5, 2.5, ACCENT);
        c.round(401 + 185 * gain, 384, 22, 22, 11, UiMotion.opacity(0x25187b70, over));
        c.round(406 + 185 * gain, 390, 12, 12, 6, ACCENT);
        c.text(tr("player.volume", (int) Math.round(gain * 100)), 812, 400, 14, TEXT);
        hit(400, 377, 390, 34, m -> { dragging = "svc-gain"; app.voiceRedirect.gain((mouseX - 410) / 185); });
        c.fit(tr("settings.svc_gain_hint"), 244, 449, 12, MUTED, 790);
        region(c, 220, 492, 880, 104, PANEL);
        c.text(tr("settings.voice_requirements"), 244, 529, 16, TEXT);
        c.fit(tr("settings.voice_requirements_hint"), 244, 560, 12, MUTED, 790);
    }
    private void keySettings(Canvas c) {
        region(c, 220, 170, 430, 426, PANEL);
        c.text(tr("settings.keys"), 244, 207, 19, TEXT);
        String[] actions = {"open", "pause", "next", "hud", "interact"};
        String[] labels = {tr("settings.key.open"), tr("settings.key.pause"), tr("settings.key.next"), tr("settings.key.hud"), tr("settings.key.interact")};
        for (int i = 0; i < actions.length; i++) {
            int y = 233 + i * 65;
            c.text(labels[i], 244, y + 25, 14, TEXT);
            c.round(472, y, 154, 39, 12, CARD);
            c.fit(host.binding(actions[i]), 484, y + 25, 13, TEXT, 130);
            if (i < actions.length - 1) c.line(244, y + 52, 626, y + 52, 1, BORDER);
        }
        region(c, 662, 170, 438, 202, PANEL);
        c.text(tr("settings.library_keys"), 686, 207, 19, TEXT);
        c.fit(tr("settings.library_keys_line1"), 686, 248, 13, TEXT, 390);
        c.fit(tr("settings.library_keys_line2"), 686, 280, 13, TEXT, 390);
        c.fit(tr("settings.library_keys_line3"), 686, 328, 12, MUTED, 390);
        region(c, 662, 384, 438, 212, PANEL);
        c.text(tr("settings.rebind"), 686, 421, 19, TEXT);
        c.fit(tr("settings.rebind_line1"), 686, 458, 13, TEXT, 390);
        c.fit(tr("settings.rebind_line2"), 686, 490, 13, TEXT, 390);
        c.fit(tr("settings.rebind_hint"), 686, 548, 12, MUTED, 390);
    }
    private void openBrowser(boolean foldersOnly) {
        this.foldersOnly = foldersOnly; switchPage("browser"); fileSelection.clear(); scrollState("browser").reset();
        browse(directory);
    }
    private void browse(Path path) {
        int generation = listingVersion.incrementAndGet(); browserError = tr("browser.loading");
        Thread.ofVirtual().name("InGameMusic-browser").start(() -> {
            try (var stream = Files.list(path)) {
                var entries = stream.filter(p -> !p.getFileName().toString().equals(".ingamemusic-trash"))
                        .filter(p -> Files.isDirectory(p) || Track.supported(p))
                        .map(p -> new Entry(p, Files.isDirectory(p)))
                        .sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(e -> e.path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).toList();
                if (generation == listingVersion.get()) { directory = path.toAbsolutePath().normalize(); files = entries; browserError = ""; }
            } catch (Exception e) { if (generation == listingVersion.get()) { files = List.of(); browserError = tr("error.directory", e.getMessage()); } }
        });
        address.value = path.toAbsolutePath().normalize().toString(); address.cursor = address.anchor = address.value.length();
        scrollState("browser").reset(); fileAnchor = -1; fileSelection.clear();
    }
    private void goAddress() {
        try { browse(Path.of(address.value.strip())); focus = null; }
        catch (Exception e) { browserError = tr("error.path", e.getMessage()); }
    }
    private void browser(Canvas c) {
        region(c, 220, 114, 880, 482, BG);
        c.text(foldersOnly ? tr("action.add_folder") : tr("action.choose_files"), 236, 65, 27, TEXT);
        c.text(tr("browser.help"), 236, 94, 12, MUTED);
        address.paint(c, 236, 122, 677, 38, tr("browser.path_placeholder"), focus == address); hit(236, 122, 677, 38, m -> focus = address);
        button(c, tr("action.go"), 923, 122, 69, 38, true, this::goAddress);
        button(c, tr("action.parent"), 1002, 122, 71, 38, false, () -> { if (directory.getParent() != null) browse(directory.getParent()); });
        int rootX = 236;
        button(c, tr("action.home"), rootX, 176, 86, 29, false, () -> browse(Path.of(System.getProperty("user.home")))); rootX += 95;
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (rootX > 955) break;
            button(c, root.toString(), rootX, 176, 70, 29, false, () -> browse(root)); rootX += 79;
        }
        var entries = files;
        var rows = beginList(c, "browser", BROWSER, entries.size());
        if (!browserError.isEmpty()) c.fit(browserError, 249, 270, 13, DANGER, 780);
        else for (int i = rows.first(); i < rows.end(); i++) {
            Entry entry = entries.get(i); int index = i, y = rows.y(i);
            boolean selected = fileSelection.contains(entry.path);
            c.glass(236, y, 837, 39, 12, selected ? SELECTED : PANEL);
            c.round(236, y, 837, 39, 12, UiMotion.opacity(0x15187b70, motion.value("file:" + entry.path, hover(236, y, 837, 39))));
            c.icon(entry.directory ? "folder" : "music", 250, y + 10, 19, entry.directory ? ACCENT : MUTED);
            c.fit(entry.path.getFileName().toString(), 288, y + 26, 14, TEXT, 680);
            c.text(entry.directory ? tr("browser.folder") : Track.extension(entry.path).toUpperCase(Locale.ROOT), 998, y + 26, 11, MUTED);
            hit(236, y, 837, 39, mods -> selectFile(entries, index, mods));
        }
        endList(c, "browser", BROWSER, entries.size());
        c.text(tr("browser.count", fileSelection.size(), entries.size()), 236, 574, 12, MUTED);
        button(c, tr("action.back_library"), 667, 553, 121, 34, false, () -> switchLibrary(playlist));
        button(c, foldersOnly ? tr("action.add_current_folder") : tr("action.import_selection"), 800, 551, 145, 37, true, () -> {
            if (!browserError.isEmpty()) return;
            var inputs = foldersOnly ? List.of(directory) : List.copyOf(fileSelection);
            if (inputs.isEmpty()) { browserError = tr("error.select_paths"); return; }
            app.importPaths(inputs, foldersOnly); switchLibrary(playlist);
        });
        if (foldersOnly) button(c, tr("action.selected_folders"), 953, 553, 120, 34, false, () -> {
            var selected = fileSelection.stream().filter(Files::isDirectory).toList();
            if (selected.isEmpty()) { browserError = tr("error.select_folder"); return; }
            app.importPaths(selected, true); switchLibrary(playlist);
        });
    }
    private void selectFile(List<Entry> entries, int index, int mods) {
        focus = null; var entry = entries.get(index); boolean ctrl = (mods & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0, shift = (mods & GLFW_MOD_SHIFT) != 0;
        if (shift && fileAnchor >= 0) {
            if (!ctrl) fileSelection.clear();
            for (int i = Math.min(fileAnchor, index); i <= Math.min(Math.max(fileAnchor, index), entries.size() - 1); i++) fileSelection.add(entries.get(i).path);
        } else if (ctrl) { if (!fileSelection.remove(entry.path)) fileSelection.add(entry.path); fileAnchor = index; }
        else { fileSelection.clear(); fileSelection.add(entry.path); fileAnchor = index; }
        long now = System.currentTimeMillis();
        if (!ctrl && !shift && entry.directory && lastPath.equals(entry.path.toString()) && now - lastClick < 350) browse(entry.path);
        lastPath = entry.path.toString(); lastClick = now;
    }
    private void createPlaylist() {
        input(tr("action.create_playlist"), tr("dialog.create_playlist_description"), tr("playlist.new"), name -> app.submit(tr("action.create_playlist"), () -> app.library.createPlaylist(name)));
    }
    private void renamePlaylist() {
        String id = playlist; var p = app.state.playlists.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
        if (p != null) input(tr("action.rename_playlist"), tr("dialog.rename_playlist_description"), p.name, name -> { if (!name.isBlank()) app.edit(tr("action.rename_playlist"), s -> s.playlists.stream().filter(l -> l.id.equals(id)).forEach(l -> l.name = name.strip())); });
    }
    private void deletePlaylist() {
        String id = playlist;
        confirm(tr("dialog.delete_playlist"), tr("dialog.delete_playlist_description"), false, () -> { app.edit(tr("action.delete_playlist"), s -> s.playlists.removeIf(p -> p.id.equals(id))); switchLibrary(null); });
    }
    private void renameFiles() {
        var paths = List.copyOf(selection); if (paths.isEmpty()) return;
        String initial = paths.size() == 1 ? Track.stem(Path.of(paths.getFirst()).getFileName().toString()) : "{n} - {name}";
        input(tr("dialog.rename_files", paths.size()), tr("dialog.rename_description"), initial, pattern -> {
            app.stopIfSelected(paths);
            app.submit(tr("action.rename_files"), () -> { app.player.awaitStopped(); app.library.rename(paths, pattern); }); selection.clear(); inspected = null;
        });
    }
    private void removeSelected() {
        var paths = List.copyOf(selection); String id = playlist;
        app.submit(id == null ? tr("action.remove_library") : tr("action.remove_playlist"), () -> app.library.remove(paths, id)); selection.clear();
    }
    private void deleteFiles() {
        var paths = List.copyOf(selection);
        confirm(tr("dialog.delete_files", paths.size()), tr("dialog.trash_description"), true, () -> {
            app.stopIfSelected(paths); app.submit(tr("action.trash"), () -> { app.player.awaitStopped(); app.library.trash(paths); }); selection.clear(); inspected = null;
        });
    }
    private void playlistChooser(Canvas c) {
        overlayLayer = true;
        hits.clear(); viewports.clear(); c.round(10, 10, WIDTH - 20, HEIGHT - 20, 20, 0x55243548);
        c.surface(340, 155, 440, 404, 23, PANEL, app.glassStyle); c.text(tr("action.add_to_playlist"), 367, 197, 23, TEXT);
        var lists = app.state.playlists;
        var rows = beginList(c, "chooser", CHOOSER, lists.size());
        if (lists.isEmpty()) c.text(tr("playlist.none"), 367, 250, 14, MUTED);
        for (int i = rows.first(); i < rows.end(); i++) {
            var p = lists.get(i); button(c, p.name, 365, rows.y(i), 390, 39, false, () -> {
                var paths = List.copyOf(selection); app.submit(tr("action.add_to_playlist"), () -> app.library.addToPlaylist(p.id, paths)); choosePlaylist = false; selection.clear();
            });
        }
        endList(c, "chooser", CHOOSER, lists.size());
        button(c, tr("action.new_playlist"), 365, 502, 125, 34, true, this::createPlaylist);
        button(c, tr("action.cancel"), 636, 502, 119, 34, false, () -> choosePlaylist = false);
    }
    private void input(String title, String description, String value, Consumer<String> action) {
        TextEdit input = new TextEdit(value); input.anchor = 0; dialog = new Dialog(title, description, input, action, false); focus = input;
    }
    private void confirm(String title, String description, boolean danger, Runnable action) {
        dialog = new Dialog(title, description, null, s -> action.run(), danger); focus = null;
    }
    private void dialog(Canvas c) {
        overlayLayer = true;
        hits.clear(); viewports.clear(); c.round(10, 10, WIDTH - 20, HEIGHT - 20, 20, 0x55243548);
        c.surface(278, 224, 564, 264, 23, PANEL, app.glassStyle);
        c.text(dialog.title, 307, 267, 24, dialog.danger ? DANGER : TEXT);
        c.fit(dialog.description, 307, 306, 13, MUTED, 505);
        if (dialog.input != null) {
            dialog.input.paint(c, 307, 327, 506, 43, tr("dialog.input_placeholder"), focus == dialog.input);
            hit(307, 327, 506, 43, m -> focus = dialog.input);
        }
        button(c, tr("action.cancel"), 580, 420, 108, 39, false, () -> { dialog = null; focus = null; });
        button(c, dialog.danger ? tr("action.confirm_delete") : tr("action.confirm"), 704, 420, 109, 39, true, this::commitDialog);
        if (dialog.danger) c.line(717, 458, 798, 458, 2, DANGER);
    }
    private void commitDialog() {
        var current = dialog;
        if (current == null || current.input != null && current.input.value.isBlank()) return;
        dialog = null; focus = null; current.action.accept(current.input == null ? "" : current.input.value);
    }
    private void switchPage(String page) { finishAppearanceDrag(); focusedSlider = ""; this.page = page; focus = null; selection.clear(); scrollState("library").reset(); scrollState("folders").reset(); anchor = -1; }
    private void switchLibrary(String id) { playlist = id; switchPage("library"); }
    private SmoothScroll scrollState(String name) { return scrolling.computeIfAbsent(name, ignored -> new SmoothScroll()); }
    private record Rows(Viewport viewport, double offset, int count) {
        int first() { return Math.max(0, (int) Math.floor(offset / viewport.rowHeight)); }
        int end() { return Math.min(count, (int) Math.ceil((offset + viewport.h) / viewport.rowHeight)); }
        int y(int index) { return (int) Math.round(viewport.y + index * (double) viewport.rowHeight - offset); }
    }
    private Rows beginList(Canvas c, String name, Viewport viewport, int count) {
        var state = scrollState(name); state.bounds(count * (double) viewport.rowHeight, viewport.h);
        viewports.put(name, viewport); hitClip = viewport;
        c.clip(viewport.x, viewport.y, viewport.w, viewport.h);
        return new Rows(viewport, state.position(), count);
    }
    private void endList(Canvas c, String name, Viewport viewport, int count) {
        c.unclip(); hitClip = null;
        double content = count * (double) viewport.rowHeight;
        if (content <= viewport.h) return;
        int x = viewport.x + viewport.w + 5, y = viewport.y;
        var bar = new Scrollbar(y, viewport.h, content, viewport.h, scrollState(name).position());
        boolean active = dragging.equals("scroll:" + name) || hover(x - 6, y, 16, viewport.h);
        c.round(x, y, 4, viewport.h, 2, CARD);
        c.round(x - 1, bar.thumbTop(), 6, bar.thumbHeight(), 3, 0xff9eafbe);
        c.round(x - 1, bar.thumbTop(), 6, bar.thumbHeight(), 3, UiMotion.opacity(ACCENT, motion.value("bar:" + name, active)));
        hit(x - 6, y, 17, viewport.h, mods -> {
            dragging = "scroll:" + name; dragBar = bar; focus = null;
            boolean onThumb = mouseY >= bar.thumbTop() && mouseY <= bar.thumbTop() + bar.thumbHeight();
            thumbGrab = onThumb ? mouseY - bar.thumbTop() : bar.thumbHeight() / 2;
            if (onThumb) scrollState(name).jump(bar.offsetAt(mouseY, thumbGrab));
            else scrollState(name).animateTo(bar.offsetAt(mouseY, thumbGrab));
        });
    }
    private void hit(int x, int y, int w, int h, Consumer<Integer> action) {
        if (hitClip != null) {
            int right = Math.min(x + w, hitClip.x + hitClip.w), bottom = Math.min(y + h, hitClip.y + hitClip.h);
            x = Math.max(x, hitClip.x); y = Math.max(y, hitClip.y); w = right - x; h = bottom - y;
        }
        if (w > 0 && h > 0) hits.add(new Hit(x, y, w, h, action));
    }
    private boolean hover(int x, int y, int w, int h) {
        if ((dialog != null || choosePlaylist) && !overlayLayer) return false;
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h
                && (hitClip == null || mouseY >= hitClip.y && mouseY < hitClip.y + hitClip.h && mouseX >= hitClip.x && mouseX < hitClip.x + hitClip.w);
    }
    private void glassBlurToggle(Canvas c, boolean enabled) {
        // State is taken from the live style, never the asynchronous disk snapshot.
        c.round(982, 455, 94, 32, 14, 0x10ffffff);
        c.round(988, 461, 38, 20, 10, enabled ? 0x55ffffff : 0x20ffffff);
        c.round(enabled ? 1008 : 991, 464, 14, 14, 7, 0xfff9fbff);
        c.centered(tr(enabled ? "option.on" : "option.off"), 1050, 476, 12, TEXT);
        glassFeedback(c, 982, 455, 94, 32, 14);
        hit(982, 455, 94, 32, m -> changeGlassStyle(app.glassStyle.withRespectBackgroundBlur(!app.glassStyle.respectBackgroundBlur())));
    }
    private void button(Canvas c, String label, int x, int y, int w, int h, boolean primary, Runnable action) {
        String id = "button:" + x + ":" + y;
        double over = motion.value(id, hover(x, y, w, h));
        double down = motion.value(id + ":press", pressed(x, y, w, h));
        double radius = Math.min(14, h / 2.0);
        c.round(x, y + 2, w, h, radius, UiMotion.opacity(0x10243548, over * (1 - down)));
        if (app.glassStyle.enabled()) {
            double inset = down * 1.5;
            c.round(x + inset, y + inset, w - inset * 2, h - inset * 2, radius, primary ? 0x16ffffff : 0x08ffffff);
        } else c.glass(x, y, w, h, radius, primary ? ACCENT : PANEL);
        glassFeedback(c, x, y, w, h, radius);
        c.round(x, y, w, h, radius, UiMotion.opacity(app.glassStyle.enabled() ? 0x24ffffff : primary ? 0x260c433c : 0x18187b70, Math.max(over * .6, down)));
        c.text("", 0, 0, 13, TEXT);
        String display = label;
        while (display.length() > 1 && Canvas.measure(display, 13) > w - 16) display = display.substring(0, UiFonts.previousBoundary(display, display.length()));
        c.centered(display, x + w / 2.0, y + h / 2.0 + 5 + Math.round(down), 13, primary ? BG : TEXT); hit(x, y, w, h, m -> action.run());
    }
    private void iconButton(Canvas c, String icon, int x, int y, int size, Runnable action) {
        String id = "icon:" + x + ":" + y;
        double over = motion.value(id, hover(x, y, size, size));
        double down = motion.value(id + ":press", pressed(x, y, size, size));
        c.round(x, y, size, size, 10, UiMotion.opacity(app.glassStyle.enabled() ? 0x24ffffff : 0x22187b70, Math.max(over * .6, down)));
        glassFeedback(c, x, y, size, size, 10);
        c.icon(icon, x + (size - 20) / 2.0, y + (size - 20) / 2.0 + Math.round(down), 20, TEXT); hit(x, y, size, size, m -> action.run());
    }
    private void glassFeedback(Canvas c, int x, int y, int w, int h, double radius) {
        if (!app.glassStyle.enabled()) return;
        if (pressedHit == null || pressedHit.x != x || pressedHit.y != y || pressedHit.w != w || pressedHit.h != h) return;
        double time = (lastFrame - rippleStart) / 1_000_000_000.0;
        if (time < 0 || time >= .46) return;
        double progress = time / .46, expansion = 1 - Math.pow(1 - progress, 3);
        double limit = Math.min(w, h) * .44;
        // Keep the expanding circle inside the button's rounded capsule. This
        // avoids the stretched rectangular glow previously drawn behind labels.
        double cx = Math.clamp(pressX, x + h * .5, x + w - h * .5);
        double cy = Math.clamp(pressY, y + limit, y + h - limit);
        double r = 2 + (limit - 2) * expansion;
        c.clip(x, y, w, h);
        c.round(cx - r, cy - r, r * 2, r * 2, r, UiMotion.opacity(0x30ffffff, 1 - progress));
        double inner = r * .68;
        c.round(cx - inner, cy - inner, inner * 2, inner * 2, inner, UiMotion.opacity(0x28ffffff, (1 - progress) * (1 - progress)));
        c.unclip();
    }

    private boolean pressed(int x, int y, int w, int h) {
        return (pointerDown || lastFrame < pressUntil) && pressedHit != null && pressedHit.contains(x + w / 2.0, y + h / 2.0) && hover(x, y, w, h);
    }
    public boolean click(double x, double y, int button, int modifiers) {
        if (button != 0) return false; mouseX = x; mouseY = y;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) { pressedHit = hits.get(i); pointerDown = true; pressX = x; pressY = y; rippleStart = System.nanoTime(); pressUntil = rippleStart + 90_000_000L; pressedHit.action.accept(modifiers); return true; }
        focus = null; return true;
    }
    public void drag(double x, double y) {
        mouseX = x; mouseY = y;
        updateAppearanceSlider(x);
        if (dragging.equals("volume")) app.player.volume((x - 798) / 110);
        if (dragging.equals("svc-gain")) app.voiceRedirect.gain((x - 410) / 185);
        if (dragging.startsWith("scroll:") && dragBar != null) scrollState(dragging.substring(7)).jump(dragBar.offsetAt(y, thumbGrab));
    }
    public void release(double x, double y) {
        pointerDown = false;
        updateAppearanceSlider(x); finishAppearanceDrag();
        if (dragging.equals("volume")) app.persistVolume();
        if (dragging.equals("svc-gain")) app.persistVoiceGain();
        if (dragging.equals("seek") && app.player.track() != null) app.player.seek(Math.clamp((x - 387) / 289, 0, 1) * app.player.track().duration());
        dragging = ""; dragBar = null;
    }
    public void scroll(double x, double y, double amount) {
        if (dialog != null || amount == 0 || !Double.isFinite(amount) || dragging.startsWith("scroll:")) return;
        for (var entry : viewports.entrySet()) {
            Viewport viewport = entry.getValue();
            if (viewport.contains(x, y)) { scrollState(entry.getKey()).wheel(-amount * viewport.rowHeight * 1.35); return; }
        }
    }
    public boolean key(int key, int modifiers) {
        if (key == GLFW_KEY_ESCAPE) {
            if (dialog != null) { dialog = null; focus = null; }
            else if (choosePlaylist) choosePlaylist = false;
            else if (focus != null) focus = null;
            else { finishAppearanceDrag(); host.close(); } return true;
        }
        if (dialog != null) {
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) commitDialog();
            else if (focus != null) focus.key(key, modifiers, host); return true;
        }
        if (focus != null) {
            if (key == GLFW_KEY_ENTER && focus == address) goAddress();
            else focus.key(key, modifiers, host); scrollState("library").reset(); return true;
        }
        if (choosePlaylist) return true;
        if (page.equals("settings") && settingsTab.equals("appearance") && app.glassStyle.enabled() && !focusedSlider.isEmpty()
                && (key == GLFW_KEY_LEFT || key == GLFW_KEY_RIGHT || key == GLFW_KEY_HOME || key == GLFW_KEY_END)) {
            boolean blur = focusedSlider.equals("glass-blur"), mask = focusedSlider.equals("glass-mask");
            double step = blur ? .5 : .01;
            double value = blur ? app.glassStyle.blur() : mask ? 1 - app.glassStyle.maskOpacity() : app.glassStyle.refraction();
            value = key == GLFW_KEY_HOME ? (blur || mask ? 0 : 1) : key == GLFW_KEY_END ? (blur ? GlassStyle.MAX_BLUR : mask ? 1 : GlassStyle.MAX_REFRACTION)
                    : value + (key == GLFW_KEY_RIGHT ? step : -step);
            app.glassStyle = blur ? app.glassStyle.withBlur(value) : mask ? app.glassStyle.withMaskOpacity(1 - value) : app.glassStyle.withRefraction(value);
            app.persistGlassStyle(); return true;
        }
        if ((modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0 && key == GLFW_KEY_A) {
            if (page.equals("browser")) files.forEach(e -> fileSelection.add(e.path));
            else if (page.equals("library")) visible().forEach(t -> selection.add(t.path())); return true;
        }
        if (key == GLFW_KEY_SPACE) { app.toggle(); return true; }
        if (page.equals("library") && !selection.isEmpty()) {
            if (key == GLFW_KEY_DELETE) { deleteFiles(); return true; }
            if (key == GLFW_KEY_F2) { renameFiles(); return true; }
            if (key == GLFW_KEY_ENTER) { Track t = app.library.find(selection.iterator().next()); if (t != null) app.play(t, visible()); return true; }
        }
        return false;
    }
    public boolean character(String text) { if (focus == null) return false; focus.insert(text); scrollState("library").reset(); anchor = -1; selection.clear(); return true; }
    public void dispose() { finishAppearanceDrag(); pointerDown = false; pressedHit = null; listingVersion.incrementAndGet(); }
}
