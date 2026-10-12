package top.mc_plfd_host.ingamemusic.compat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class VoiceRedirectTest {
    @AfterEach void cleanup() {
        VoiceRedirect.active = null;
        VoiceRedirect.available = VoiceRedirect.connected = VoiceRedirect.disabled = false;
    }
    @Test void pluginMergesMusicWithoutReplacingMicrophoneAndRespectsGates() {
        var callbacks = new HashMap<Class<?>, Consumer<?>>();
        new SimpleVoiceChatPlugin().registerEvents(new EventRegistration() {
            public <T extends Event> void registerEvent(Class<T> type, Consumer<T> handler, int priority) { callbacks.put(type, handler); }
        });
        AtomicInteger resets = new AtomicInteger();
        var redirect = new VoiceRedirect(gain -> new short[]{(short) (10000 * gain)}, resets::incrementAndGet);
        VoiceRedirect.active = redirect;
        boolean[] disconnected = {false}, disabled = {false};
        VoicechatClientApi api = (VoicechatClientApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{VoicechatClientApi.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisconnected" -> disconnected[0];
                    case "isDisabled" -> disabled[0];
                    case "isMuted" -> true;
                    default -> null;
                });
        var merged = new ArrayList<short[]>();
        MergeClientSoundEvent event = (MergeClientSoundEvent) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{MergeClientSoundEvent.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getVoicechat")) return api;
                    if (method.getName().equals("mergeAudio")) merged.add((short[]) args[0]);
                    return null;
                });
        @SuppressWarnings("unchecked") var callback = (Consumer<MergeClientSoundEvent>) callbacks.get(MergeClientSoundEvent.class);
        callback.accept(event); assertTrue(merged.isEmpty());
        redirect.enabled(true); callback.accept(event);
        assertArrayEquals(new short[]{3500}, merged.getLast());
        redirect.gain(1.25); callback.accept(event);
        assertArrayEquals(new short[]{12500}, merged.getLast());
        int sent = merged.size();
        disabled[0] = true; callback.accept(event); assertEquals("svc.disabled", redirect.statusKey());
        disabled[0] = false; disconnected[0] = true; callback.accept(event); assertEquals("svc.disconnected", redirect.statusKey());
        disconnected[0] = false; redirect.gain(0); callback.accept(event);
        redirect.gain(1); redirect.enabled(false); callback.accept(event);
        assertEquals(sent, merged.size()); assertTrue(resets.get() >= 4);
        assertFalse(callbacks.containsKey(ClientSoundEvent.class), "The microphone must not be replaced");
    }
}
