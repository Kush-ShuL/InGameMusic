package top.mc_plfd_host.ingamemusic;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class LanguageTest {
    @Test void everyLiteralTranslationKeyExistsInBothLanguages() throws Exception {
        Path resources = Path.of("src/main/resources/assets/ingamemusic/lang");
        JsonObject zh = JsonParser.parseString(Files.readString(resources.resolve("zh_cn.json"))).getAsJsonObject();
        JsonObject en = JsonParser.parseString(Files.readString(resources.resolve("en_us.json"))).getAsJsonObject();
        assertEquals(zh.keySet(), en.keySet());
        var keys = Pattern.compile("tr\\(\"([^\"]+)\"");
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                assertFalse(Pattern.compile("[\\p{IsHan}]").matcher(text).find(), "Hardcoded Chinese text in " + source);
                var matcher = keys.matcher(text);
                while (matcher.find()) assertTrue(zh.has("ingamemusic." + matcher.group(1)), source + ": " + matcher.group(1));
            }
        }
        for (String key : zh.keySet()) {
            long first = zh.get(key).getAsString().chars().filter(c -> c == '%').count();
            long second = en.get(key).getAsString().chars().filter(c -> c == '%').count();
            assertEquals(first, second, "Placeholder mismatch: " + key);
        }
    }
}
