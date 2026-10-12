package top.mc_plfd_host.ingamemusic.compat;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.*;

/** Loaded exclusively by the optional voicechat entrypoint. SVC owns encoding and transport. */
public final class SimpleVoiceChatPlugin implements VoicechatPlugin {
    @Override public String getPluginId() { return "ingamemusic"; }
    @Override public void registerEvents(EventRegistration registration) {
        VoiceRedirect.available = true;
        registration.registerEvent(ClientVoicechatConnectionEvent.class, event -> {
            VoiceRedirect.connected = event.isConnected();
            VoiceRedirect.disabled = event.getVoicechat().isDisabled();
        });
        registration.registerEvent(VoicechatDisableEvent.class, event -> VoiceRedirect.disabled = event.isDisabled());
        registration.registerEvent(MergeClientSoundEvent.class, event -> {
            VoiceRedirect.connected = !event.getVoicechat().isDisconnected();
            VoiceRedirect.disabled = event.getVoicechat().isDisabled();
            VoiceRedirect redirect = VoiceRedirect.active;
            if (redirect == null) return;
            short[] music = redirect.frame();
            if (music != null) event.mergeAudio(music);
        });
    }
}
