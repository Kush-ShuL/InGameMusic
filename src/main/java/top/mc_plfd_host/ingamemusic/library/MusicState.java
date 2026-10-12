package top.mc_plfd_host.ingamemusic.library;

import java.util.*;

public final class MusicState {
    public int schema = 1;
    public List<String> folders = new ArrayList<>();
    public List<Track> tracks = new ArrayList<>();
    public List<Playlist> playlists = new ArrayList<>();
    public double volume = .65;
    public boolean hud = true;
    public boolean hudCompact = false;
    public boolean generatedCovers = false;
    public boolean simpleVoiceChatRedirect = false;
    public double svcGain = .35;
    public double hudX = .02, hudY = .08;
    public String mode = "ORDER";
    public static final class Playlist {
        public String id = UUID.randomUUID().toString();
        public String name;
        public List<String> tracks = new ArrayList<>();
        public Playlist(String name) { this.name = name; }
    }
}
