package top.mc_plfd_host.ingamemusic.compat;

import org.junit.jupiter.api.Test;
import top.mc_plfd_host.ingamemusic.audio.AudioPlayer;
import java.net.*;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoiceChatCompatibilityTest {
    @Test void localPlayerLoadsWithoutVoicechatClasses() throws Exception {
        URL main = AudioPlayer.class.getProtectionDomain().getCodeSource().getLocation();
        try (var isolated = new URLClassLoader(new URL[]{main}, ClassLoader.getPlatformClassLoader())) {
            Class<?> playerClass = isolated.loadClass(AudioPlayer.class.getName());
            Object player = playerClass.getConstructor().newInstance();
            Class<?> redirectClass = isolated.loadClass(VoiceRedirect.class.getName());
            Object redirect = redirectClass.getConstructor(playerClass).newInstance(player);
            assertEquals(false, redirectClass.getMethod("enabled").invoke(redirect));
            assertNull(redirectClass.getMethod("frame").invoke(redirect));
            playerClass.getMethod("close").invoke(player);
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("de.maxhenkel.voicechat.api.VoicechatPlugin"));
        }
    }
    @Test void pluginRegistersAgainstInstalledSvcJar() throws Exception {
        String path = System.getProperty("voicechat.testJar");
        assumeTrue(path != null);
        URL main = AudioPlayer.class.getProtectionDomain().getCodeSource().getLocation();
        try (var isolated = new URLClassLoader(new URL[]{main, Path.of(path).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> plugin = isolated.loadClass(SimpleVoiceChatPlugin.class.getName());
            Class<?> registration = isolated.loadClass("de.maxhenkel.voicechat.api.events.EventRegistration");
            List<String> events = new ArrayList<>();
            Object receiver = Proxy.newProxyInstance(isolated, new Class[]{registration}, (p, method, args) -> {
                if (method.getName().equals("registerEvent")) events.add(((Class<?>) args[0]).getName());
                return null;
            });
            Object instance = plugin.getConstructor().newInstance();
            plugin.getMethod("registerEvents", registration).invoke(instance, receiver);
            assertEquals(3, events.size());
            assertTrue(events.contains("de.maxhenkel.voicechat.api.events.MergeClientSoundEvent"));
            assertNotNull(isolated.loadClass("de.maxhenkel.voicechat.api.events.MergeClientSoundEvent").getMethod("mergeAudio", short[].class));
        }
    }
}
