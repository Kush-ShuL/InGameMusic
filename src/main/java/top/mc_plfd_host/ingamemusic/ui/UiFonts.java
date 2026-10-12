package top.mc_plfd_host.ingamemusic.ui;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Shared shaping and bundled font fallback for drawing, fitting and editing. */
final class UiFonts {
    static final FontRenderContext METRICS = new FontRenderContext(null, true, false);
    static final Font TEXT = load("notosanssc.ttf");
    static final Font EMOJI = load("notoemoji.ttf");
    private static final Pattern CLUSTER = Pattern.compile("\\X");

    private UiFonts() { }
    private static Font load(String resource) {
        try (var input = UiFonts.class.getResourceAsStream("/assets/ingamemusic/font/" + resource)) {
            if (input == null) throw new IllegalStateException("Missing bundled font: " + resource);
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (Exception failure) {
            System.getLogger(UiFonts.class.getName()).log(System.Logger.Level.WARNING, "Cannot load font " + resource, failure);
            return new Font(Font.SANS_SERIF, Font.PLAIN, 16);
        }
    }
    static TextLayout layout(String text, float size, boolean bold) {
        var primary = TEXT.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
        var emoji = EMOJI.deriveFont(Font.PLAIN, size);
        var attributed = new AttributedString(text);
        attributed.addAttribute(TextAttribute.FONT, primary);
        var clusters = CLUSTER.matcher(text);
        while (clusters.find()) {
            if (usesEmoji(clusters.group())) {
                attributed.addAttribute(TextAttribute.FONT, emoji, clusters.start(), clusters.end());
                attributed.addAttribute(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON, clusters.start(), clusters.end());
            }
        }
        return new TextLayout(attributed.getIterator(), METRICS);
    }
    static boolean usesEmoji(String cluster) {
        boolean emojiPresentation = cluster.indexOf('\ufe0f') >= 0 || cluster.indexOf('\u20e3') >= 0;
        boolean textPresentation = cluster.indexOf('\ufe0e') >= 0;
        boolean missing = false, covered = true;
        for (int codePoint : cluster.codePoints().toArray()) {
            if (codePoint == 0xfe0e || codePoint == 0xfe0f || Character.getType(codePoint) == Character.FORMAT) continue;
            emojiPresentation |= codePoint >= 0x1f000 && codePoint <= 0x1faff;
            missing |= !TEXT.canDisplay(codePoint);
            covered &= EMOJI.canDisplay(codePoint);
        }
        return covered && (missing || emojiPresentation && !textPresentation);
    }
    static int width(String text, float size, boolean bold) {
        return text.isEmpty() ? 0 : (int) Math.ceil(layout(text, size, bold).getAdvance());
    }
    static int[] boundaries(String text) {
        return IntStream.concat(IntStream.of(0), CLUSTER.matcher(text).results().mapToInt(m -> m.end())).toArray();
    }
    static int previousBoundary(String text, int offset) {
        int previous = 0;
        var clusters = CLUSTER.matcher(text);
        while (clusters.find() && clusters.end() < offset) previous = clusters.end();
        return previous;
    }
    static int nextBoundary(String text, int offset) {
        var clusters = CLUSTER.matcher(text);
        while (clusters.find()) if (clusters.end() > offset) return clusters.end();
        return text.length();
    }
}
