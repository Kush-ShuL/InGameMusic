package top.mc_plfd_host.ingamemusic.ui;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Hash-seeded fractal noise, domain warping and local vortices, finished with Gaussian blur. */
public final class GeneratedCover {
    public static final int SIZE = 256;
    private GeneratedCover() { }
    public static byte[] hash(Path path) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }
    public static BufferedImage render(byte[] hash) {
        if (hash.length != 32) throw new IllegalArgumentException("Expected SHA-256 digest");
        long seed = 0;
        for (byte b : hash) seed = Long.rotateLeft(seed, 7) ^ (b & 255);
        var random = new Random(seed);
        float hue = random.nextFloat();
        float accent = hue + .28f + random.nextFloat() * .22f;
        int[] colors = {
                Color.HSBtoRGB(hue + .07f, .65f, .22f),
                Color.HSBtoRGB(hue, .70f, .63f),
                Color.HSBtoRGB(hue + .10f, .44f, .93f),
                Color.HSBtoRGB(accent, .60f, .82f),
                Color.HSBtoRGB(accent + .07f, .18f, .98f)
        };
        double angle = random.nextDouble() * Math.PI * 2, cosine = Math.cos(angle), sine = Math.sin(angle);
        double zoom = 1.3 + random.nextDouble() * .6;
        double phase = random.nextDouble() * Math.PI * 2;
        double[] cx = new double[3], cy = new double[3], twist = new double[3], radius = new double[3];
        for (int i = 0; i < cx.length; i++) {
            cx[i] = random.nextDouble() * 1.6 - .8;
            cy[i] = random.nextDouble() * 1.6 - .8;
            twist[i] = (random.nextBoolean() ? 1 : -1) * (1.8 + random.nextDouble() * 3);
            radius[i] = .35 + random.nextDouble() * .65;
        }
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int x = 0; x < SIZE; x++) {
                double xx = (x + .5) / SIZE * 2 - 1, yy = (y + .5) / SIZE * 2 - 1;
                double u = (xx * cosine - yy * sine) * zoom, v = (xx * sine + yy * cosine) * zoom;
                for (int i = 0; i < cx.length; i++) {
                    double dx = u - cx[i], dy = v - cy[i];
                    double rotation = twist[i] * Math.exp(-(dx * dx + dy * dy) / radius[i]);
                    double c = Math.cos(rotation), s = Math.sin(rotation);
                    u = cx[i] + dx * c - dy * s; v = cy[i] + dx * s + dy * c;
                }
                double qx = fractal(seed, u * 1.4 + 13.7, v * 1.4 - 8.2);
                double qy = fractal(seed ^ 0x51ed270bL, u * 1.4 - 4.8, v * 1.4 + 17.3);
                double rx = fractal(seed ^ 0x9e3779b9L, u * 2 + qx * 2.1, v * 2 + qy * 2.1);
                double ry = fractal(seed ^ 0x7f4a7c15L, u * 2 + qx * 2.1 + 9, v * 2 + qy * 2.1 - 6);
                u += qx * 1.4 + rx * .65; v += qy * 1.4 + ry * .65;
                double cloud = fractal(seed ^ 0x632be59bL, u * 1.3, v * 1.3);
                double ribbons = Math.sin(u * 5 + v * 3 + rx * 5 + phase);
                double value = Math.clamp(.5 + cloud * .95 + ribbons * .16 + ry * .14, 0, 1);
                pixels[y * SIZE + x] = palette(colors, value);
            }
        }
        int blurRadius = 18; double sigma = 6;
        double[] kernel = new double[blurRadius * 2 + 1]; double sum = 0;
        for (int i = -blurRadius; i <= blurRadius; i++) { double weight = Math.exp(-i * i / (2 * sigma * sigma)); kernel[i + blurRadius] = weight; sum += weight; }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        pixels = blur(blur(pixels, kernel, blurRadius, true), kernel, blurRadius, false);
        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, SIZE, SIZE, pixels, 0, SIZE);
        return image;
    }
    private static int palette(int[] colors, double value) {
        double at = value * (colors.length - 1);
        int index = Math.min(colors.length - 2, (int) at);
        double blend = at - index; blend = blend * blend * (3 - 2 * blend);
        int a = colors[index], b = colors[index + 1], result = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int first = a >> shift & 255, last = b >> shift & 255;
            result |= (int) Math.round(first + (last - first) * blend) << shift;
        }
        return result;
    }
    private static double fractal(long seed, double x, double y) {
        double sum = 0, weight = .57;
        for (int octave = 0; octave < 4; octave++) {
            sum += noise(seed + octave * 0x9e3779b97f4a7c15L, x, y) * weight;
            double nextX = x * 1.62 - y * 1.27 + 7.1;
            y = x * 1.27 + y * 1.62 - 3.4; x = nextX; weight *= .48;
        }
        return sum;
    }
    private static double noise(long seed, double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double dx = x - ix, dy = y - iy;
        double u = fade(dx), v = fade(dy);
        double a = gradient(seed, ix, iy, dx, dy), b = gradient(seed, ix + 1, iy, dx - 1, dy);
        double c = gradient(seed, ix, iy + 1, dx, dy - 1), d = gradient(seed, ix + 1, iy + 1, dx - 1, dy - 1);
        return ((a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v) * 1.4;
    }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double gradient(long seed, int x, int y, double dx, double dy) {
        long hash = seed ^ x * 0x632be59bd9b4e019L ^ y * 0x9e3779b97f4a7c15L;
        hash = (hash ^ hash >>> 30) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ hash >>> 27) * 0x94d049bb133111ebL;
        return switch ((int) (hash ^ hash >>> 31) & 7) {
            case 0 -> dx; case 1 -> -dx; case 2 -> dy; case 3 -> -dy;
            case 4 -> (dx + dy) * .7071067811865476;
            case 5 -> (dx - dy) * .7071067811865476;
            case 6 -> (-dx + dy) * .7071067811865476;
            default -> (-dx - dy) * .7071067811865476;
        };
    }
    private static int[] blur(int[] source, double[] kernel, int radius, boolean horizontal) {
        int[] result = new int[source.length];
        for (int y = 0; y < SIZE; y++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int x = 0; x < SIZE; x++) {
                double r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = horizontal ? Math.clamp(x + k, 0, SIZE - 1) : x;
                    int yy = horizontal ? y : Math.clamp(y + k, 0, SIZE - 1);
                    int color = source[yy * SIZE + xx]; double weight = kernel[k + radius];
                    r += (color >> 16 & 255) * weight; g += (color >> 8 & 255) * weight; b += (color & 255) * weight;
                }
                result[y * SIZE + x] = 0xff000000 | (int) Math.round(r) << 16 | (int) Math.round(g) << 8 | (int) Math.round(b);
            }
        }
        return result;
    }
}
