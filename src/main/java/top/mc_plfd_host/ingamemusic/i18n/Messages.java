package top.mc_plfd_host.ingamemusic.i18n;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/** Minecraft installs its resource-pack-aware translator; tools/tests read the same lang JSON. */
public final class Messages {
    private static final Map<String, String> FALLBACK = load("zh_cn");
    private static volatile BiFunction<String, Object[], String> translator = Messages::fallback;
    private Messages() { }
    public static void install(BiFunction<String, Object[], String> provider) { translator = Objects.requireNonNull(provider); }
    public static String tr(String key, Object... args) {
        String fullKey = "ingamemusic." + key;
        String text = translator.apply(fullKey, args);
        return text.equals(fullKey) ? fallback(fullKey, args) : text;
    }
    private static String fallback(String key, Object[] args) {
        return String.format(Locale.ROOT, FALLBACK.getOrDefault(key, key), args);
    }
    private static Map<String, String> load(String language) {
        try (var stream = Messages.class.getResourceAsStream("/assets/ingamemusic/lang/" + language + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing language resource: " + language);
            var object = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> values = new HashMap<>(); object.entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
            return Map.copyOf(values);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
