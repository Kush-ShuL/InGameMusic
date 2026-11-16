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
    private int width = WIDTH, height = HEIGHT;
    private int extraWidth() { return width - WIDTH; }
    private int extraHeight() { return height - HEIGHT; }
    private int detailWidth() { return Math.min(340, 266 + extraWidth() / 4); }
    private int detailX() { return width - 20 - detailWidth(); }
    private int libraryWidth() { return detailX() - 262; }
    public void resize(int width, int height) {
        finishAppearanceDrag();
        this.width = Math.max(WIDTH, width); this.height = Math.max(HEIGHT, height);
        dragging = ""; dragBar = null; pressedHit = null; pointerDown = false;
    }
    private final Map<String, SmoothScroll> scrolling = new HashMap<>();
    private final Map<String, Viewport> viewports = new LinkedHashMap<>();
    private Viewport hitClip;
    private long lastFrame;
    private final UiMotion motion = new UiMotion();
    private Hit pressedHit;
    private boolean pointerDown;
    private long pressUntil;
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
        try (Canvas c = new Canvas(width, height, app.glassStyle.enabled())) {
            region(c, 12, 12, 194, height - 136, 0xffedf2f6);
            region(c, 220, 12, width - 232, 90, BG);
            sidebar(c);
            if (page.equals("browser")) browser(c);
            else if (page.equals("folders")) folders(c);
            else if (page.equals("settings")) settings(c);
            else library(c);
            player(c);
            String notice = app.player.status() == AudioPlayer.Status.ERROR ? tr("error.playback", app.player.error()) : app.notice;
            c.fit(notice, 36, height - 95, 12, app.player.status() == AudioPlayer.Status.ERROR ? DANGER : MUTED, width - 72);
            iconButton(c, "close", width - 54, 28, 30, host::close);
            if (choosePlaylist) playlistChooser(c);
            if (dialog != null) dialog(c);
            return c.finish();
        }
    }
    /** Independent surfaces leave the game visible through the gutters. */
    private void region(Canvas c, int x, int y, int width, int height, int tint) {
        c.surface(x, y, width, height, 18, tint, app.glassStyle);
    }
    private void sidebar(Canvas c) {
        c.glass(32, 35, 36, 36, 12, ACCENT); c.icon("music", 39, 42, 22, BG);
        c.text(tr("brand.name"), 80, 56, 20, TEXT); c.text(tr("brand.subtitle"), 33, 96, 12, MUTED);
        nav(c, "grid", tr("nav.library"), 112, page.equals("library") && playlist == null, () -> switchLibrary(null));
        nav(c, "folder", tr("nav.folders"), 156, page.equals("folders"), () -> switchPage("folders"));
        nav(c, "settings", tr("nav.settings"), 200, page.equals("settings"), () -> switchPage("settings"));
        c.text(tr("nav.playlists"), 33, 278, 12, MUTED);
        iconButton(c, "plus", 159, 258, 25, this::createPlaylist);
        var lists = app.state.playlists;
        var viewport = new Viewport(25, 296, 164, 250 + extraHeight(), 40);
        var rows = beginList(c, "sidebar", viewport, lists.size());
        for (int i = rows.first(); i < rows.end(); i++) {
            var list = lists.get(i); int y = rows.y(i);
            if (list.id.equals(playlist)) c.glass(25, y, 164, 34, 12, SELECTED);
            c.round(25, y, 164, 34, 12, UiMotion.opacity(0x16187b70, motion.value("playlist:" + list.id, hover(25, y, 164, 34))));
            c.icon("list", 35, y + 9, 16, list.id.equals(playlist) ? ACCENT : MUTED);
            c.fit(list.name, 62, y + 22, 14, TEXT, 114);
            hit(25, y, 164, 34, m -> switchLibrary(list.id));
        }
        endList(c, "sidebar", viewport, lists.size());
    }
    private void nav(Canvas c, String icon, String label, int y, boolean active, Runnable action) {
        boolean hover = hover(25, y, 164, 40);
        if (active) c.glass(25, y, 164, 40, 14, SELECTED);
        double warmth = motion.value("nav:" + icon, hover);
        c.round(25, y, 164, 40, 14, UiMotion.opacity(0x15187b70, warmth));
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
        int rowWidth = libraryWidth(), rowRight = 236 + rowWidth;
        int albumX = 566 + (rowWidth - 572) * 3 / 5, durationX = rowRight - 53;
        var viewport = new Viewport(236, 208, rowWidth, 378 + extraHeight(), 54);
        region(c, 220, 114, rowWidth + 30, 482 + extraHeight(), BG);
        List<Track> tracks = visible();
        String title = playlist == null ? tr("library.title") : app.state.playlists.stream().filter(p -> p.id.equals(playlist)).map(p -> p.name).findFirst().orElse(tr("playlist.generic"));
        c.fit(title, 236, 65, 28, TEXT, width - 810);
        c.text(tr("library.count", tracks.size()), 236, 88, 12, MUTED);
        search.paint(c, width - 550, 35, 472, 38, tr("search.placeholder"), focus == search);
        hit(width - 550, 35, 472, 38, m -> focus = search);
        if (selection.isEmpty()) {
            button(c, tr("action.play_all"), 236, 126, 92, 36, true, () -> { if (!tracks.isEmpty()) app.play(tracks.getFirst(), tracks); });
            button(c, tr("action.import_files"), 336, 126, 104, 36, false, () -> openBrowser(false));
            button(c, tr("action.add_folder"), 448, 126, 104, 36, false, () -> openBrowser(true));
            if (playlist != null) {
                button(c, tr("action.rename_playlist"), 560, 126, 110, 36, false, this::renamePlaylist);
                button(c, tr("action.delete_playlist"), 678, 126, 130, 36, false, this::deletePlaylist);
            }
            c.text(tr("library.double_click"), 236, 179, 12, MUTED);
        } else {
            button(c, tr("action.add_to_playlist"), 236, 126, 104, 36, true, () -> { choosePlaylist = true; scrollState("chooser").reset(); focus = null; });
            button(c, tr("action.rename"), 348, 126, 88, 36, false, this::renameFiles);
            button(c, tr("action.remove"), 444, 126, 72, 36, false, this::removeSelected);
            button(c, tr("action.delete_files"), 524, 126, 100, 36, false, this::deleteFiles);
            button(c, tr("action.clear_selection"), 632, 126, 112, 36, false, selection::clear);
            c.text(tr("selection.count", selection.size()), 236, 179, 12, ACCENT);
        }
        c.text(tr("column.track"), 276, 193, 12, MUTED);
        c.text(tr("column.album"), albumX, 193, 12, MUTED); c.text(tr("column.duration"), durationX, 193, 12, MUTED);
        if (tracks.isEmpty()) {
            int center = 236 + rowWidth / 2, middle = extraHeight() / 2;
            c.icon("music", center - 27, 316 + middle, 54, ACCENT);
            c.centered(search.value.isEmpty() ? tr("empty.library") : tr("empty.search"), center, 411 + middle, 20, TEXT);
            c.centered(tr("empty.library_hint"), center, 442 + middle, 12, MUTED);
            button(c, tr("action.choose_files"), center - 75, 466 + middle, 150, 38, true, () -> openBrowser(false));
        }
        var rows = beginList(c, "library", viewport, tracks.size());
        for (int i = rows.first(); i < rows.end(); i++) {
            Track t = tracks.get(i); int y = rows.y(i); int index = i;
            boolean selected = selection.contains(t.path()), playing = app.player.track() != null && app.player.track().path().equals(t.path());
            if (selected) c.glass(236, y, rowWidth, 51, 13, SELECTED);
            double rowHover = motion.value("track:" + t.path(), hover(236, y, rowWidth, 51));
            c.round(236, y, rowWidth, 51, 13, UiMotion.opacity(0x11187b70, rowHover));
            c.round(247, y + 19, 13, 13, 3, selected ? ACCENT : TRACK);
            if (selected) c.icon("check", 247, y + 19, 13, BG);
            c.cover(covers.get(t, app.state.generatedCovers), 275, y + 7, 36, t.album().hashCode());
            c.fit(t.title(), 324, y + 23, 14, playing ? ACCENT : TEXT, albumX - 346);
            c.fit(t.displayArtist(), 324, y + 42, 12, MUTED, albumX - 346);
            c.fit(t.displayAlbum(), albumX, y + 31, 12, MUTED, durationX - albumX - 25);
            c.text(t.duration() > 0 ? time(t.duration()) : tr("value.unknown"), durationX, y + 31, 12, MUTED);
            hit(236, y, rowWidth, 51, mods -> selectTrack(tracks, index, mods));
        }
        endList(c, "library", viewport, tracks.size());
        details(c);
    }
    private void details(Canvas c) {
        Track t = inspected == null ? app.player.track() : trackIndex.get(inspected);
        int x = detailX(), inner = detailWidth() - 40, bottom = height - 124;
        int coverSize = Math.min(inner, 272 + extraHeight());
        region(c, x, 114, detailWidth() + 8, bottom - 114, PANEL);
        c.text(t == null ? tr("details.empty_title") : tr("details.title"), x + 20, 140, 12, MUTED);
        c.cover(t == null ? null : covers.get(t, app.state.generatedCovers), x + (detailWidth() - coverSize) / 2, 157, coverSize, t == null ? 174 : t.album().hashCode());
        c.fit(t == null ? tr("details.empty_track") : t.title(), x + 20, 187 + coverSize, 20, TEXT, inner);
        c.fit(t == null ? tr("details.empty_artist") : t.displayArtist(), x + 20, 211 + coverSize, 14, MUTED, inner);
        c.text(tr("column.composer"), x + 20, bottom - 76, 12, MUTED);
        c.fit(t == null ? tr("value.unknown") : t.displayComposer(), x + 92, bottom - 76, 12, TEXT, inner - 72);
        c.text(tr("column.album"), x + 20, bottom - 44, 12, MUTED);
        c.fit(t == null ? tr("value.unknown") : t.displayAlbum(), x + 92, bottom - 44, 12, TEXT, inner - 72);
        c.fit(t == null ? tr("details.client_only") : t.file().getFileName().toString(), x + 20, bottom - 16, 12, MUTED, inner);
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
        int center = extraWidth() / 2, bottom = extraHeight(), right = extraWidth();
        region(c, 12, height - 112, width - 24, 100, PANEL);
        Track t = app.player.track();
        c.cover(t == null ? null : covers.get(t, app.state.generatedCovers), 30, 642 + bottom, 52, t == null ? 174 : t.album().hashCode());
        c.fit(t == null ? tr("player.empty") : t.title(), 96, 662 + bottom, 14, TEXT, 221 + center);
        c.fit(t == null ? tr("player.empty_hint") : t.displayArtist(), 96, 684 + bottom, 12, MUTED, 221 + center);
        iconButton(c, "prev", 375 + center, 641 + bottom, 32, () -> app.next(true, false));
        c.glass(420 + center, 635 + bottom, 44, 44, 22, ACCENT);
        double playHover = motion.value("play-hover", hover(420 + center, 635 + bottom, 44, 44));
        double playPress = motion.value("play-press", pressed(420 + center, 635 + bottom, 44, 44));
        c.round(420 + center, 635 + bottom, 44, 44, 22, UiMotion.opacity(app.glassStyle.enabled() ? 0x30ffffff : 0x16092e29, Math.max(playHover * .55, playPress)));
        c.icon(isPlaying() ? "pause" : "play", 431 + center, 646 + bottom + Math.round(playPress * 2), 22, BG);
        hit(420 + center, 635 + bottom, 44, 44, m -> app.toggle());
        iconButton(c, "next", 477 + center, 641 + bottom, 32, () -> app.next(false, false));
        button(c, app.modeLabel(), 532 + center, 640 + bottom, 94, 31, false, app::cycleMode);
        double shownPosition = app.displayPosition(lastFrame);
        double fraction = t != null && t.duration() > 0 ? Math.clamp(shownPosition / t.duration(), 0, 1) : 0;
        if (dragging.equals("seek") && t != null && t.duration() > 0) { fraction = seekFraction(mouseX); shownPosition = fraction * t.duration(); }
        c.text(time(shownPosition), 340 + center, 698 + bottom, 12, MUTED);
        c.round(387 + center, 689 + bottom, 289, 4, 2, TRACK); c.round(387 + center, 689 + bottom, 289 * fraction, 4, 2, ACCENT);
        double seekHover = motion.value("seek-hover", hover(383 + center, 680 + bottom, 297, 22) || dragging.equals("seek"));
        c.round(383 + center + 289 * fraction, 687 + bottom, 8, 8, 4, UiMotion.opacity(ACCENT, seekHover));
        c.text(t == null || t.duration() <= 0 ? tr("value.unknown") : time(t.duration()), 688 + center, 698 + bottom, 12, MUTED);
        hit(383 + center, 680 + bottom, 297, 22, m -> dragging = "seek");
        c.icon("volume", 765 + right, 658 + bottom, 20, MUTED);
        c.round(798 + right, 667 + bottom, 110, 4, 2, TRACK); c.round(798 + right, 667 + bottom, 110 * app.player.volume(), 4, 2, ACCENT);
        double volumeHover = motion.value("volume-hover", hover(790 + right, 651 + bottom, 129, 34) || dragging.equals("volume"));
        c.round(788 + right + 110 * app.player.volume(), 657 + bottom, 23, 23, 11, UiMotion.opacity(0x25187b70, volumeHover));
        c.round(794 + right + 110 * app.player.volume(), 663 + bottom, 11, 11, 5, ACCENT);
        hit(790 + right, 651 + bottom, 129, 34, m -> { dragging = "volume"; app.player.volume(volumeFraction(mouseX)); });
        c.text(tr("player.volume", (int) (app.player.volume() * 100)), 923 + right, 674 + bottom, 12, MUTED);
        button(c, tr("action.mini"), 986 + right, 650 + bottom, 96, 36, false, host::editHud);
        if (app.player.status() == AudioPlayer.Status.LOADING) c.text(tr("player.loading"), 638 + center, 659 + bottom, 12, ACCENT);
    }
    private double seekFraction(double x) { return Math.clamp((x - 387 - extraWidth() / 2) / 289, 0, 1); }
    private double volumeFraction(double x) { return (x - 798 - extraWidth()) / 110; }
    private boolean isPlaying() { return app.player.status() == AudioPlayer.Status.PLAYING || app.player.status() == AudioPlayer.Status.LOADING; }
    private void folders(Canvas c) {
        int extra = extraWidth();
        var viewport = new Viewport(236, 188, 837 + extra, 396 + extraHeight(), 66);
        region(c, 220, 114, 888 + extra, 482 + extraHeight(), BG);
        c.text(tr("nav.folders"), 236, 65, 28, TEXT);
        c.text(tr("folders.description"), 236, 94, 14, MUTED);
        button(c, tr("action.add_folder_plus"), 236, 122, 128, 37, true, () -> openBrowser(true));
        button(c, tr("action.rescan_all"), 376, 122, 130, 37, false, () -> app.importPaths(app.state.folders.stream().map(Path::of).toList(), false));
        var rows = beginList(c, "folders", viewport, app.state.folders.size());
        for (int i = rows.first(); i < rows.end(); i++) {
            String path = app.state.folders.get(i); int y = rows.y(i);
            c.glass(236, y, 837 + extra, 56, 13, PANEL); c.icon("folder", 252, y + 16, 23, ACCENT);
            c.fit(path, 292, y + 34, 14, TEXT, 540 + extra);
            button(c, tr("action.scan"), 860 + extra, y + 11, 75, 33, false, () -> app.importPaths(List.of(Path.of(path)), false));
            button(c, tr("action.unlink"), 944 + extra, y + 11, 112, 33, false, () -> app.edit(tr("action.unlink_folder"), s -> s.folders.remove(path)));
        }
        endList(c, "folders", viewport, app.state.folders.size());
        if (app.state.folders.isEmpty()) c.text(tr("folders.empty"), 236, 278, 14, MUTED);
    }
    private void settings(Canvas c) {
        c.text(tr("settings.title"), 236, 65, 28, TEXT);
        c.text(tr("settings.subtitle"), 236, 88, 12, MUTED);
        String[] tabs = {"appearance", "playback", "voice", "keys"};
        String[] labels = {tr("settings.tab.appearance"), tr("settings.tab.playback"), tr("settings.tab.voice"), tr("settings.tab.keys")};
        for (int i = 0; i < tabs.length; i++) {
            String tab = tabs[i];
            int tabWidth = (width - 232 + 12) / 4;
            button(c, labels[i], 220 + i * tabWidth, 118, tabWidth - 12, 38, settingsTab.equals(tab), () -> {
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
        int grow = appearanceGrowth(), right = 784 + grow, sideWidth = width - 20 - right;
        int bottom = extraHeight(), split = bottom / 2;
        region(c, 220, 170, 552 + grow, 426 + bottom, PANEL);
        c.text(tr("settings.material"), 244, 205, 20, TEXT);
        c.fit(tr("settings.material_hint"), 244, 228, 12, MUTED, 504 + grow);
        button(c, tr("option.material_flat"), 244, 246, 244 + grow / 2, 40, !style.enabled(), () -> changeGlassStyle(app.glassStyle.withEnabled(false)));
        button(c, tr("option.material_glass"), 500 + grow / 2, 246, 248 + grow - grow / 2, 40, style.enabled(), () -> changeGlassStyle(app.glassStyle.withEnabled(true)));
        appearanceSlider(c, "glass-blur", tr("settings.glass_blur"), tr("settings.glass_blur_hint"), 318 + bottom / 6,
                style.blur() / GlassStyle.MAX_BLUR, tr("settings.blur_value", String.format(Locale.ROOT, "%.1f", style.blur())), style.enabled());
        appearanceSlider(c, "glass-refraction", tr("settings.glass_refraction"), tr("settings.glass_refraction_hint"), 404 + split,
                (style.refraction() - 1) / (GlassStyle.MAX_REFRACTION - 1), String.format(Locale.ROOT, "%.2f", style.refraction()), style.enabled());
        appearanceSlider(c, "glass-mask", tr("settings.glass_mask"), tr("settings.glass_mask_hint"), 490 + bottom * 5 / 6,
                1 - style.maskOpacity(), tr("settings.percent_value", (int) Math.round((1 - style.maskOpacity()) * 100)), style.enabled());
        c.fit(tr(style.enabled() ? "settings.glass_live" : "settings.glass_disabled"), 244, 584 + bottom, 12, MUTED, 504 + grow);

        region(c, right, 170, sideWidth + 8, 260 + split, PANEL);
        c.text(tr("settings.preview"), right + 24, 205, 20, TEXT);
        c.fit(tr("settings.preview_hint"), right + 24, 231, 12, MUTED, sideWidth - 48);
        c.round(right + 24, 254 + split / 2, 48, 48, 15, SELECTED); c.icon("music", right + 36, 266 + split / 2, 24, ACCENT);
        c.text(tr("brand.name"), right + 88, 275 + split / 2, 20, TEXT);
        c.fit(tr("settings.preview_caption"), right + 88, 297 + split / 2, 12, MUTED, sideWidth - 112);
        button(c, tr("settings.preview_button"), right + 24, 325 + split / 2, sideWidth - 48, 42, true, () -> {});
        c.fit(tr("settings.preview_note"), right + 24, 402 + split, 12, MUTED, sideWidth - 48);
        region(c, right, 442 + split, sideWidth + 8, 154 + bottom - split, PANEL);
        c.text(tr("settings.respect_blur"), right + 24, 478 + split, 14, TEXT);
        if (style.enabled()) glassBlurToggle(c, width - 138, 455 + split, style.respectBackgroundBlur());
        else { c.round(width - 138, 455 + split, 94, 32, 12, CARD); c.centered(tr(style.respectBackgroundBlur() ? "option.on" : "option.off"), width - 91, 476 + split, 12, MUTED); }
        c.fit(tr("settings.respect_blur_hint"), right + 24, 514 + split, 12, MUTED, sideWidth - 48);
        c.fit(tr("settings.respect_blur_hint_off"), right + 24, 537 + split, 12, MUTED, sideWidth - 48);
        button(c, tr("action.reset_appearance"), right + 24, 554 + bottom, sideWidth - 48, 30, false,
                () -> changeGlassStyle(new GlassStyle(app.glassStyle.enabled(), GlassStyle.DEFAULT_BLUR, GlassStyle.DEFAULT_REFRACTION, true)));
    }
    private void appearanceSlider(Canvas c, String id, String label, String hint, int baseline, double fraction, String value, boolean enabled) {
        int grow = appearanceGrowth(), length = 410 + grow;
        c.text(label, 244, baseline, 14, enabled ? TEXT : MUTED);
        c.fit(hint, 244, baseline + 23, 12, MUTED, 504 + grow);
        c.round(244, baseline + 46, length, 5, 2.5, TRACK);
        c.round(244, baseline + 46, length * fraction, 5, 2.5, enabled ? ACCENT : MUTED);
        double over = motion.value(id + ":hover", enabled && (hover(236, baseline + 33, length + 16, 31) || dragging.equals(id)));
        c.round(233 + length * fraction, baseline + 37, 23, 23, 11.5, UiMotion.opacity(0x25187b70, over));
        c.round(240 + length * fraction, baseline + 42, 12, 12, 6, enabled ? ACCENT : MUTED);
        c.round(681 + grow, baseline + 32, 67, 32, 10, CARD);
        c.centered(value, 714 + grow, baseline + 53, 12, enabled ? TEXT : MUTED);
        if (enabled) hit(236, baseline + 33, length + 16, 31, m -> {
            dragging = id; focusedSlider = id; focus = null; updateAppearanceSlider(mouseX);
        });
    }
    private int appearanceGrowth() { return extraWidth() * 2 / 3; }
    private void updateAppearanceSlider(double x) {
        double fraction = Math.clamp((x - 244) / (410 + appearanceGrowth()), 0, 1);
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
        int grow = extraWidth() / 2, right = 662 + grow, secondGrow = extraWidth() - grow, bottom = extraHeight();
        region(c, 220, 170, 430 + grow, 192, PANEL);
        c.text(tr("settings.hud"), 244, 207, 20, TEXT);
        c.fit(tr("settings.hud_description"), 244, 237, 12, MUTED, 382 + grow);
        button(c, app.state.hud ? tr("option.on") : tr("option.off"), 244, 290, 146, 40, app.state.hud, () -> app.edit(tr("action.hud"), state -> state.hud = !state.hud));
        button(c, tr("action.position"), 404, 290, 222 + grow, 40, false, host::editHud);
        region(c, right, 170, 446 + secondGrow, 192, PANEL);
        c.text(tr("settings.hud_style"), right + 24, 207, 20, TEXT);
        c.fit(tr("settings.hud_style_hint"), right + 24, 237, 12, MUTED, 390 + secondGrow);
        button(c, tr("option.hud_full"), right + 24, 290, 188 + secondGrow / 2, 40, !app.state.hudCompact, () -> app.edit(tr("action.hud_style"), state -> state.hudCompact = false));
        button(c, tr("option.hud_compact"), right + 224 + secondGrow / 2, 290, 190 + secondGrow - secondGrow / 2, 40, app.state.hudCompact, () -> app.edit(tr("action.hud_style"), state -> state.hudCompact = true));
        region(c, 220, 374, width - 232, 134 + bottom / 2, PANEL);
        c.text(tr("settings.generated_covers"), 244, 411, 20, TEXT);
        c.fit(tr("settings.generated_covers_hint"), 244, 442, 12, MUTED, 650 + extraWidth());
        c.fit(tr("settings.covers_note"), 244, 480 + bottom / 2, 12, MUTED, 780 + extraWidth());
        button(c, app.state.generatedCovers ? tr("option.on") : tr("option.off"), width - 180, 401, 136, 40, app.state.generatedCovers,
                () -> app.edit(tr("action.generated_covers"), state -> state.generatedCovers = !state.generatedCovers));
        region(c, 220, 520 + bottom / 2, width - 232, 76 + bottom - bottom / 2, PANEL);
        c.text(tr("settings.playback_note"), 244, 565 + bottom / 2, 14, MUTED);
    }
    private void voiceSettings(Canvas c) {
        int extra = extraWidth(), bottom = extraHeight();
        region(c, 220, 170, width - 232, 310 + bottom / 2, PANEL);
        c.text(tr("settings.svc_redirect"), 244, 207, 20, TEXT);
        button(c, app.voiceRedirect.enabled() ? tr("option.on") : tr("option.off"), width - 180, 187, 136, 40,
                app.voiceRedirect.enabled(), app::toggleVoiceRedirect);
        c.fit(tr("settings.svc_hint"), 244, 242, 14, MUTED, 790 + extra);
        c.fit(tr("settings.svc_mute_hint"), 244, 271, 12, MUTED, 790 + extra);
        c.round(244, 292, 832 + extra, 40, 12, CARD);
        c.fit(tr(app.voiceRedirect.statusKey()), 260, 318, 14, TEXT, 800 + extra);
        c.text(tr("settings.svc_gain"), 244, 400 + bottom / 4, 14, TEXT);
        double gain = app.voiceRedirect.gain();
        double over = motion.value("svc-gain-hover", hover(400, 377 + bottom / 4, 390 + extra, 34) || dragging.equals("svc-gain"));
        double filled = (370 + extra) * gain / 2;
        c.round(410, 393 + bottom / 4, 370 + extra, 5, 2.5, TRACK); c.round(410, 393 + bottom / 4, filled, 5, 2.5, ACCENT);
        c.round(401 + filled, 384 + bottom / 4, 22, 22, 11, UiMotion.opacity(0x25187b70, over));
        c.round(406 + filled, 390 + bottom / 4, 12, 12, 6, ACCENT);
        c.text(tr("player.volume", (int) Math.round(gain * 100)), 812 + extra, 400 + bottom / 4, 14, TEXT);
        hit(400, 377 + bottom / 4, 390 + extra, 34, m -> { dragging = "svc-gain"; app.voiceRedirect.gain(voiceGain(mouseX)); });
        c.fit(tr("settings.svc_gain_hint"), 244, 449 + bottom / 2, 12, MUTED, 790 + extra);
        region(c, 220, 492 + bottom / 2, width - 232, 104 + bottom - bottom / 2, PANEL);
        c.text(tr("settings.voice_requirements"), 244, 529 + bottom / 2, 20, TEXT);
        c.fit(tr("settings.voice_requirements_hint"), 244, 560 + bottom / 2, 12, MUTED, 790 + extra);
    }
    private double voiceGain(double x) { return (x - 410) * 2 / (370 + extraWidth()); }
    private void keySettings(Canvas c) {
        int grow = extraWidth() / 2, right = 662 + grow, secondGrow = extraWidth() - grow, bottom = extraHeight();
        region(c, 220, 170, 430 + grow, 426 + bottom, PANEL);
        c.text(tr("settings.keys"), 244, 207, 20, TEXT);
        String[] actions = {"open", "pause", "next", "hud", "interact"};
        String[] labels = {tr("settings.key.open"), tr("settings.key.pause"), tr("settings.key.next"), tr("settings.key.hud"), tr("settings.key.interact")};
        for (int i = 0; i < actions.length; i++) {
            int y = 233 + i * (65 + bottom / 5);
            c.text(labels[i], 244, y + 25, 14, TEXT);
            c.round(472 + grow, y, 154, 39, 12, CARD);
            c.fit(host.binding(actions[i]), 484 + grow, y + 25, 14, TEXT, 130);
        }
        region(c, right, 170, 446 + secondGrow, 202 + bottom / 2, PANEL);
        c.text(tr("settings.library_keys"), right + 24, 207, 20, TEXT);
        c.fit(tr("settings.library_keys_line1"), right + 24, 248, 14, TEXT, 390 + secondGrow);
        c.fit(tr("settings.library_keys_line2"), right + 24, 280, 14, TEXT, 390 + secondGrow);
        c.fit(tr("settings.library_keys_line3"), right + 24, 328 + bottom / 2, 12, MUTED, 390 + secondGrow);
        region(c, right, 384 + bottom / 2, 446 + secondGrow, 212 + bottom - bottom / 2, PANEL);
        c.text(tr("settings.rebind"), right + 24, 421 + bottom / 2, 20, TEXT);
        c.fit(tr("settings.rebind_line1"), right + 24, 458 + bottom / 2, 14, TEXT, 390 + secondGrow);
        c.fit(tr("settings.rebind_line2"), right + 24, 490 + bottom / 2, 14, TEXT, 390 + secondGrow);
        c.fit(tr("settings.rebind_hint"), right + 24, 548 + bottom, 12, MUTED, 390 + secondGrow);
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
        int extra = extraWidth(), bottom = extraHeight();
        var viewport = new Viewport(236, 216, 837 + extra, 322 + bottom, 46);
        region(c, 220, 114, 888 + extra, 482 + bottom, BG);
        c.text(foldersOnly ? tr("action.add_folder") : tr("action.choose_files"), 236, 65, 28, TEXT);
        c.text(tr("browser.help"), 236, 94, 12, MUTED);
        address.paint(c, 236, 122, 677 + extra, 38, tr("browser.path_placeholder"), focus == address); hit(236, 122, 677 + extra, 38, m -> focus = address);
        button(c, tr("action.go"), 923 + extra, 122, 69, 38, true, this::goAddress);
        button(c, tr("action.parent"), 1002 + extra, 122, 71, 38, false, () -> { if (directory.getParent() != null) browse(directory.getParent()); });
        int rootX = 236;
        button(c, tr("action.home"), rootX, 176, 86, 29, false, () -> browse(Path.of(System.getProperty("user.home")))); rootX += 95;
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (rootX > 955 + extra) break;
            button(c, root.toString(), rootX, 176, 70, 29, false, () -> browse(root)); rootX += 79;
        }
        var entries = files;
        var rows = beginList(c, "browser", viewport, entries.size());
        if (!browserError.isEmpty()) c.fit(browserError, 249, 270, 14, DANGER, 780 + extra);
        else for (int i = rows.first(); i < rows.end(); i++) {
            Entry entry = entries.get(i); int index = i, y = rows.y(i);
            boolean selected = fileSelection.contains(entry.path);
            c.glass(236, y, 837 + extra, 39, 12, selected ? SELECTED : PANEL);
            c.round(236, y, 837 + extra, 39, 12, UiMotion.opacity(0x15187b70, motion.value("file:" + entry.path, hover(236, y, 837 + extra, 39))));
            c.icon(entry.directory ? "folder" : "music", 250, y + 10, 19, entry.directory ? ACCENT : MUTED);
            c.fit(entry.path.getFileName().toString(), 288, y + 26, 14, TEXT, 680 + extra);
            c.text(entry.directory ? tr("browser.folder") : Track.extension(entry.path).toUpperCase(Locale.ROOT), 998 + extra, y + 26, 12, MUTED);
            hit(236, y, 837 + extra, 39, mods -> selectFile(entries, index, mods));
        }
        endList(c, "browser", viewport, entries.size());
        c.text(tr("browser.count", fileSelection.size(), entries.size()), 236, 574 + bottom, 12, MUTED);
        button(c, tr("action.back_library"), 667 + extra, 553 + bottom, 121, 34, false, () -> switchLibrary(playlist));
        button(c, foldersOnly ? tr("action.add_current_folder") : tr("action.import_selection"), 800 + extra, 551 + bottom, 145, 37, true, () -> {
            if (!browserError.isEmpty()) return;
            var inputs = foldersOnly ? List.of(directory) : List.copyOf(fileSelection);
            if (inputs.isEmpty()) { browserError = tr("error.select_paths"); return; }
            app.importPaths(inputs, foldersOnly); switchLibrary(playlist);
        });
        if (foldersOnly) button(c, tr("action.selected_folders"), 953 + extra, 553 + bottom, 120, 34, false, () -> {
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
        int x = (width - 440) / 2, y = (height - 404) / 2;
        var viewport = new Viewport(x + 25, y + 65, 390, 240, 48);
        hits.clear(); viewports.clear(); c.round(0, 0, width, height, 0, 0x55243548);
        c.surface(x, y, 440, 404, 23, PANEL, app.glassStyle); c.text(tr("action.add_to_playlist"), x + 27, y + 42, 20, TEXT);
        var lists = app.state.playlists;
        var rows = beginList(c, "chooser", viewport, lists.size());
        if (lists.isEmpty()) c.text(tr("playlist.none"), x + 27, y + 95, 14, MUTED);
        for (int i = rows.first(); i < rows.end(); i++) {
            var p = lists.get(i); button(c, p.name, x + 25, rows.y(i), 390, 39, false, () -> {
                var paths = List.copyOf(selection); app.submit(tr("action.add_to_playlist"), () -> app.library.addToPlaylist(p.id, paths)); choosePlaylist = false; selection.clear();
            });
        }
        endList(c, "chooser", viewport, lists.size());
        button(c, tr("action.new_playlist"), x + 25, y + 347, 125, 34, true, this::createPlaylist);
        button(c, tr("action.cancel"), x + 296, y + 347, 119, 34, false, () -> choosePlaylist = false);
    }
    private void input(String title, String description, String value, Consumer<String> action) {
        TextEdit input = new TextEdit(value); input.anchor = 0; dialog = new Dialog(title, description, input, action, false); focus = input;
    }
    private void confirm(String title, String description, boolean danger, Runnable action) {
        dialog = new Dialog(title, description, null, s -> action.run(), danger); focus = null;
    }
    private void dialog(Canvas c) {
        overlayLayer = true;
        int x = (width - 564) / 2, y = (height - 264) / 2;
        hits.clear(); viewports.clear(); c.round(0, 0, width, height, 0, 0x55243548);
        c.surface(x, y, 564, 264, 23, PANEL, app.glassStyle);
        c.text(dialog.title, x + 29, y + 43, 20, dialog.danger ? DANGER : TEXT);
        c.fit(dialog.description, x + 29, y + 82, 14, MUTED, 505);
        if (dialog.input != null) {
            dialog.input.paint(c, x + 29, y + 103, 506, 43, tr("dialog.input_placeholder"), focus == dialog.input);
            hit(x + 29, y + 103, 506, 43, m -> focus = dialog.input);
        }
        button(c, tr("action.cancel"), x + 302, y + 196, 108, 39, false, () -> { dialog = null; focus = null; });
        button(c, dialog.danger ? tr("action.confirm_delete") : tr("action.confirm"), x + 426, y + 196, 109, 39, true, this::commitDialog);
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
    private void glassBlurToggle(Canvas c, int x, int y, boolean enabled) {
        // State is taken from the live style, never the asynchronous disk snapshot.
        c.round(x, y, 94, 32, 14, 0x10ffffff);
        c.round(x + 6, y + 6, 38, 20, 10, enabled ? 0x55ffffff : 0x20ffffff);
        c.round(x + (enabled ? 26 : 9), y + 9, 14, 14, 7, 0xfff9fbff);
        c.centered(tr(enabled ? "option.on" : "option.off"), x + 68, y + 21, 12, TEXT);
        hit(x, y, 94, 32, m -> changeGlassStyle(app.glassStyle.withRespectBackgroundBlur(!app.glassStyle.respectBackgroundBlur())));
    }
    private void button(Canvas c, String label, int x, int y, int w, int h, boolean primary, Runnable action) {
        String id = "button:" + x + ":" + y;
        double over = motion.value(id, hover(x, y, w, h));
        double down = motion.value(id + ":press", pressed(x, y, w, h));
        double radius = Math.min(14, h / 2.0);
        if (app.glassStyle.enabled()) {
            c.round(x, y, w, h, radius, primary ? 0x24ffffff : 0x0cffffff);
        } else c.glass(x, y, w, h, radius, primary ? ACCENT : PANEL);
        c.round(x, y, w, h, radius, UiMotion.opacity(app.glassStyle.enabled() ? 0x24ffffff : primary ? 0x260c433c : 0x18187b70, Math.max(over * .6, down)));
        String display = label;
        while (display.length() > 1 && Canvas.measure(display, 14) > w - 16) display = display.substring(0, UiFonts.previousBoundary(display, display.length()));
        c.centered(display, x + w / 2.0, y + h / 2.0 + 5 + Math.round(down), 14, primary ? BG : TEXT); hit(x, y, w, h, m -> action.run());
    }
    private void iconButton(Canvas c, String icon, int x, int y, int size, Runnable action) {
        String id = "icon:" + x + ":" + y;
        double over = motion.value(id, hover(x, y, size, size));
        double down = motion.value(id + ":press", pressed(x, y, size, size));
        c.round(x, y, size, size, 10, UiMotion.opacity(app.glassStyle.enabled() ? 0x24ffffff : 0x22187b70, Math.max(over * .6, down)));
        c.icon(icon, x + (size - 20) / 2.0, y + (size - 20) / 2.0 + Math.round(down), 20, TEXT); hit(x, y, size, size, m -> action.run());
    }
    private boolean pressed(int x, int y, int w, int h) {
        return (pointerDown || lastFrame < pressUntil) && pressedHit != null && pressedHit.contains(x + w / 2.0, y + h / 2.0) && hover(x, y, w, h);
    }
    public boolean click(double x, double y, int button, int modifiers) {
        if (button != 0) return false; mouseX = x; mouseY = y;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) { pressedHit = hits.get(i); pointerDown = true; pressUntil = System.nanoTime() + 90_000_000L; pressedHit.action.accept(modifiers); return true; }
        focus = null; return true;
    }
    public void drag(double x, double y) {
        mouseX = x; mouseY = y;
        updateAppearanceSlider(x);
        if (dragging.equals("volume")) app.player.volume(volumeFraction(x));
        if (dragging.equals("svc-gain")) app.voiceRedirect.gain(voiceGain(x));
        if (dragging.startsWith("scroll:") && dragBar != null) scrollState(dragging.substring(7)).jump(dragBar.offsetAt(y, thumbGrab));
    }
    public void release(double x, double y) {
        pointerDown = false;
        updateAppearanceSlider(x); finishAppearanceDrag();
        if (dragging.equals("volume")) app.persistVolume();
        if (dragging.equals("svc-gain")) app.persistVoiceGain();
        if (dragging.equals("seek") && app.player.track() != null) app.player.seek(seekFraction(x) * app.player.track().duration());
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
