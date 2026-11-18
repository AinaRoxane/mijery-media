package mdg.mijery.media.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Image processing via libvips (CLI). Re-encodes, strips EXIF, generates variants at
 * fixed widths in WebP, and creates watermarked variants from a product's logo.
 *
 * <p>Requires {@code vips} and {@code vipsheader} on the host
 * ({@code apt install libvips-tools}). When they are missing the service degrades
 * explicitly: variants are byte copies of the original and no watermarked variant is
 * produced. Callers must treat a {@code null} watermarked result as "not watermarked"
 * rather than assuming one exists.
 */
@Service
public class ImageProcessingService {

    private final String vipsBin;
    private final String vipsheaderBin;
    private final List<Integer> variantWidths;
    private final boolean vipsAvailable;
    private final boolean vipsheaderAvailable;

    public ImageProcessingService(
            @Value("${mijery.media.vips-bin}") String vipsBin,
            @Value("${mijery.media.variant-widths}") String variantWidthsStr) {
        this.vipsBin = vipsBin;
        this.vipsheaderBin = resolveVipsheader(vipsBin);
        this.variantWidths = Arrays.stream(variantWidthsStr.split(","))
                .map(String::trim).map(Integer::parseInt).toList();
        this.vipsAvailable = probe(vipsBin);
        this.vipsheaderAvailable = probe(vipsheaderBin);
        if (!vipsAvailable) {
            System.err.println("WARNING: vips binary not usable at '" + vipsBin +
                    "'. Image variants will be unprocessed copies of the original." +
                    " Install libvips-tools for WebP optimization and watermarks.");
        } else if (!vipsheaderAvailable) {
            System.err.println("WARNING: vipsheader binary not usable at '" + vipsheaderBin +
                    "'. Watermarked variants are disabled because image dimensions cannot be read.");
        }
    }

    private static String resolveVipsheader(String vipsBin) {
        Path path = Path.of(vipsBin);
        Path parent = path.getParent();
        if (parent != null) {
            return parent.resolve("vipsheader").toString();
        }
        return "vipsheader";
    }

    private static boolean probe(String binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binary, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) {
                is.readAllBytes();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAvailable() {
        return vipsAvailable;
    }

    public boolean isWatermarkingAvailable() {
        return vipsAvailable && vipsheaderAvailable;
    }

    /**
     * Re-encode the original to strip EXIF and normalize format.
     * If vips is unavailable, returns the original bytes unchanged.
     */
    public byte[] reencodeOriginal(Path input) throws IOException, InterruptedException {
        if (!vipsAvailable) {
            return Files.readAllBytes(input);
        }
        Path output = temp(input.getParent(), "clean", ".webp");
        try {
            runVips("thumbnail", input.toString(), output.toString(),
                    "--size", "99999", // keep original dimensions
                    "-o", "[Q=90]");
            return Files.readAllBytes(output);
        } finally {
            deleteQuietly(output);
        }
    }

    /**
     * Generate a variant at the given width in WebP.
     * If vips is unavailable, returns the original bytes unchanged.
     */
    public byte[] generateVariant(Path original, int width) throws IOException, InterruptedException {
        if (!vipsAvailable) {
            return Files.readAllBytes(original);
        }
        Path output = temp(original.getParent(), "var_" + width, ".webp");
        try {
            runVips("thumbnail", original.toString(), output.toString(),
                    String.valueOf(width), "-o", "[Q=85]");
            return Files.readAllBytes(output);
        } finally {
            deleteQuietly(output);
        }
    }

    /**
     * Generate a watermarked variant from the product's logo.
     *
     * <p>The overlay is scaled to roughly 12% of the target width, then composited in
     * the bottom-right corner with a margin. The logo file handed in here is the
     * pre-rendered {@code watermark.webp} (alpha already at the configured opacity), so
     * this method only resizes and positions.
     *
     * @return the encoded bytes, or {@code null} when vips is unavailable or there is
     *         no usable logo — never a silently unwatermarked copy
     */
    public byte[] generateWatermarkedVariant(Path original, int width, Path watermark)
            throws IOException, InterruptedException {
        if (!isWatermarkingAvailable() || watermark == null || !Files.exists(watermark)) {
            return null;
        }
        Path dir = original.getParent();
        Path base = temp(dir, "base_" + width, ".webp");
        Path overlay = temp(dir, "wm_" + width, ".webp");
        Path output = temp(dir, "wmout_" + width, ".webp");
        try {
            runVips("thumbnail", original.toString(), base.toString(),
                    String.valueOf(width), "-o", "[Q=85]");

            int[] baseSize = imageSize(base);
            if (baseSize[0] <= 1 || baseSize[1] <= 1) {
                return null;
            }

            int overlayWidth = Math.min(baseSize[0] / 3, Math.max(24, baseSize[0] * 12 / 100));
            runVips("thumbnail", watermark.toString(), overlay.toString(),
                    String.valueOf(overlayWidth));
            int[] overlaySize = imageSize(overlay);
            if (overlaySize[0] <= 0 || overlaySize[1] <= 0) {
                return null;
            }

            int margin = Math.max(8, baseSize[0] / 100);
            int x = Math.max(0, baseSize[0] - overlaySize[0] - margin);
            int y = Math.max(0, baseSize[1] - overlaySize[1] - margin);

            runVips("composite2", base.toString(), overlay.toString(), output.toString(),
                    "--x", String.valueOf(x), "--y", String.valueOf(y));
            return Files.readAllBytes(output);
        } finally {
            deleteQuietly(base, overlay, output);
        }
    }

    /**
     * Normalizes an uploaded product logo: longest side capped at 512 px, WebP output,
     * alpha preserved, metadata dropped.
     *
     * @return true when the normalized file was written; false when vips is unavailable
     *         and the caller must store the original bytes instead
     */
    public boolean normalizeLogo(Path input, Path output) throws IOException, InterruptedException {
        if (!vipsAvailable) {
            return false;
        }
        Path temp = temp(output.getParent(), "logo", ".webp");
        try {
            runVips("thumbnail", input.toString(), temp.toString(), "512", "-o", "[Q=90]");
            Files.move(temp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Renders the watermark overlay used when compositing: the logo capped at 512 px
     * with its alpha channel scaled by {@code opacityPercent}.
     *
     * @return true when {@code output} was written; false when vips is unavailable or
     *         the input has no colour bands that can be composited
     */
    public boolean renderWatermarkOverlay(Path input, Path output, int opacityPercent)
            throws IOException, InterruptedException {
        if (!isWatermarkingAvailable()) {
            return false;
        }
        Path dir = output.getParent();
        Path base = temp(dir, "wmbase", ".png");
        Path srgb = temp(dir, "wmsrgb", ".png");
        Path four = temp(dir, "wmfour", ".png");
        Path rgb = temp(dir, "wmrgb", ".v");
        Path alpha = temp(dir, "wmalpha", ".v");
        Path scaledAlpha = temp(dir, "wmalpha_s", ".v");
        try {
            runVips("thumbnail", input.toString(), base.toString(), "512");
            int bands = imageBands(base);
            Path coloured = base;

            if (bands == 2) {
                // Grey + alpha: colourspace may keep the alpha band.
                runVips("colourspace", base.toString(), srgb.toString(), "srgb");
                coloured = srgb;
                bands = imageBands(coloured);
            }
            if (bands == 3) {
                runVips("bandjoin_const", coloured.toString(), four.toString(), "255");
                coloured = four;
                bands = imageBands(coloured);
            }
            if (bands != 4) {
                System.err.println("WARNING: logo '" + input.getFileName()
                        + "' has " + bands + " bands; watermark overlay skipped.");
                return false;
            }

            double factor = Math.max(0, Math.min(100, opacityPercent)) / 100.0;
            if (factor >= 1.0) {
                Files.copy(coloured, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            }

            runVips("extract_band", coloured.toString(), rgb.toString(), "0", "3");
            runVips("extract_band", coloured.toString(), alpha.toString(), "3", "1");
            runVips("linear", alpha.toString(), scaledAlpha.toString(),
                    String.valueOf(factor), "0");
            runVips("bandjoin", rgb + " " + scaledAlpha, output.toString());
            return Files.exists(output) && Files.size(output) > 0;
        } finally {
            deleteQuietly(base, srgb, four, rgb, alpha, scaledAlpha);
        }
    }

    /** Image dimensions as {@code [width, height]}, or {@code [0, 0]} when unknown. */
    public int[] imageSize(Path image) {
        if (!vipsheaderAvailable) {
            return new int[]{0, 0};
        }
        try {
            int width = Integer.parseInt(capture(vipsheaderBin, "-f", "width", image.toString()));
            int height = Integer.parseInt(capture(vipsheaderBin, "-f", "height", image.toString()));
            return new int[]{width, height};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private int imageBands(Path image) throws IOException, InterruptedException {
        return Integer.parseInt(capture(vipsheaderBin, "-f", "bands", image.toString()));
    }

    public List<Integer> getVariantWidths() {
        return variantWidths;
    }

    private void runVips(String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(concat(vipsBin, args));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain output
        try (InputStream is = process.getInputStream()) {
            is.readAllBytes();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("vips command failed with exit code " + exitCode);
        }
    }

    private static String capture(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0 || output.isEmpty()) {
            throw new IOException("Command failed: " + String.join(" ", command));
        }
        // vipsheader may print warnings before the value; the value is the last line.
        String[] lines = output.split("\\R");
        return lines[lines.length - 1].trim();
    }

    private static Path temp(Path dir, String prefix, String suffix) {
        return dir.resolve("." + prefix + "-" + UUID.randomUUID() + suffix);
    }

    private static void deleteQuietly(Path... paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    private static String[] concat(String first, String... rest) {
        String[] result = new String[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
