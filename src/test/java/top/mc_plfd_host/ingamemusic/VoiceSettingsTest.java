package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.mc_plfd_host.ingamemusic.library.Library;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VoiceSettingsTest {
    @TempDir Path home;
    @Test void oldLibrariesDefaultOffAndSettingsSurviveReload() throws Exception {
        Files.writeString(home.resolve("library.json"), "{\"tracks\":[],\"playlists\":[],\"folders\":[]}");
        try (var app = new MusicController(home)) {
            assertFalse(app.voiceRedirect.enabled()); assertEquals(.35, app.voiceRedirect.gain());
            app.toggleVoiceRedirect(); app.voiceRedirect.gain(1.25); app.persistVoiceGain();
            app.submit("barrier", () -> {});
            long deadline = System.nanoTime() + 3_000_000_000L;
            while ((!app.state.simpleVoiceChatRedirect || app.state.svcGain != 1.25) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(app.state.simpleVoiceChatRedirect); assertEquals(1.25, app.state.svcGain);
        }
        try (var reloaded = new MusicController(home)) {
            assertTrue(reloaded.voiceRedirect.enabled()); assertEquals(1.25, reloaded.voiceRedirect.gain());
            reloaded.library.edit(s -> s.svcGain = 99);
        }
        assertEquals(2, new Library(home).snapshot().svcGain);
    }
}
