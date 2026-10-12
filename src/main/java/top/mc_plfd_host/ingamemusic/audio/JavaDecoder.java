package top.mc_plfd_host.ingamemusic.audio;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import io.github.jseproject.*;
import javax.sound.sampled.*;
import javax.sound.sampled.spi.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import top.mc_plfd_host.ingamemusic.library.Track;

/** Explicit providers avoid ServiceLoader/context-classloader surprises under Fabric Knot. */
public final class JavaDecoder {
    private JavaDecoder() {}
    private record Codec(AudioFileReader reader, FormatConversionProvider converter) {}
    private static Codec codec(Path path) throws IOException {
        return switch (Track.extension(path)) {
            case "mp3" -> new Codec(new Mp3AudioFileReader(), new Mp3FormatConversionProvider());
            case "flac" -> new Codec(new FlacAudioFileReader(), new FlacFormatConversionProvider());
            case "aac" -> new Codec(new AACAudioFileReader(), new AACFormatConversionProvider());
            case "opus" -> new Codec(new OpusAudioFileReader(), new OpusFormatConversionProvider());
            case "ogg", "oga" -> {
                byte[] head;
                try (var input = Files.newInputStream(path)) { head = input.readNBytes(64); }
                boolean opus = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).contains("OpusHead");
                yield opus ? new Codec(new OpusAudioFileReader(), new OpusFormatConversionProvider())
                        : new Codec(new VorbisAudioFileReader(), new VorbisFormatConversionProvider());
            }
            case "wav" -> null;
            default -> throw new IOException(tr("error.format", path.getFileName()));
        };
    }
    private static AudioInputStream guard(AudioInputStream decoded) {
        // The JDK wrapper handles zero-length reads before entering third-party SPI code.
        return new AudioInputStream(decoded, decoded.getFormat(), decoded.getFrameLength()) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length < getFormat().getFrameSize()) return 0;
                return super.read(bytes, offset, length);
            }
        };
    }
    public static AudioFileFormat inspect(Path path) throws Exception {
        Codec codec = codec(path);
        return codec == null ? AudioSystem.getAudioFileFormat(path.toFile()) : codec.reader.getAudioFileFormat(path.toFile());
    }
    /** Returns signed 16-bit little-endian PCM, preserving source sample rate and channel count. */
    public static AudioInputStream open(Path path) throws Exception {
        if (Track.extension(path).equals("flac")) return FlacPcmStream.open(path);
        Codec codec = codec(path);
        if (codec != null && codec.reader instanceof OpusAudioFileReader) return OpusPcmStream.open(path);
        InputStream file = new BufferedInputStream(Files.newInputStream(path), 65536);
        AudioInputStream source = null;
        try {
            source = codec == null ? AudioSystem.getAudioInputStream(file) : codec.reader.getAudioInputStream(file);
            AudioFormat input = source.getFormat();
            if (input.getChannels() < 1 || input.getChannels() > 2)
                throw new IOException(tr("error.channels"));
            AudioFormat target = new AudioFormat(input.getSampleRate(), 16, input.getChannels(), true, false);
            if (target.matches(input)) return source;
            if (codec != null && codec.reader instanceof AACAudioFileReader) {
                // JAAD emits big-endian samples regardless of the SPI's requested endianness.
                AudioFormat nativePcm = new AudioFormat(input.getSampleRate(), 16, input.getChannels(), true, true);
                source = codec.converter.getAudioInputStream(nativePcm, source);
                return guard(AudioSystem.getAudioInputStream(target, source));
            }
            if (codec != null) {
                if (codec.converter.isConversionSupported(target, input)) return guard(codec.converter.getAudioInputStream(target, source));
                AudioFormat[] formats = codec.converter.getTargetFormats(AudioFormat.Encoding.PCM_SIGNED, input);
                if (formats.length == 0) throw new IOException(tr("error.audio_format", input));
                AudioFormat candidate = Arrays.stream(formats).filter(f -> !f.isBigEndian()).findFirst().orElse(formats[0]);
                source = codec.converter.getAudioInputStream(candidate, source);
            }
            return guard(AudioSystem.getAudioInputStream(target, source));
        } catch (Exception e) {
            if (source != null) source.close();
            file.close(); throw e;
        }
    }
}
