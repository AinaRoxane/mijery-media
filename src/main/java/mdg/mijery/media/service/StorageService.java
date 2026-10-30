package mdg.mijery.media.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local filesystem storage. In production, swap for S3-compatible storage
 * by implementing the same interface.
 */
@Service
public class StorageService {

    private final Path root;

    public StorageService(@Value("${mijery.media.storage-dir}") String storageDir) {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve("originals"));
            Files.createDirectories(root.resolve("variants"));
        } catch (IOException e) {
            throw new RuntimeException("Cannot create storage directories", e);
        }
    }

    public Path storeOriginal(UUID assetId, InputStream data, String ext) throws IOException {
        Path dir = root.resolve("originals");
        Files.createDirectories(dir);
        Path target = dir.resolve(assetId + ext);
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public Path storeVariant(UUID assetId, int width, String format, boolean watermarked, byte[] data) throws IOException {
        Path dir = root.resolve("variants").resolve(assetId.toString());
        Files.createDirectories(dir);
        String suffix = watermarked ? "-wm" : "";
        Path target = dir.resolve(width + suffix + "." + format);
        Files.write(target, data);
        return target;
    }

    public Path resolveOriginal(UUID assetId, String ext) {
        return root.resolve("originals").resolve(assetId + ext);
    }

    public Path resolveVariant(UUID assetId, int width, boolean watermarked, String format) {
        String suffix = watermarked ? "-wm" : "";
        return root.resolve("variants").resolve(assetId.toString()).resolve(width + suffix + "." + format);
    }

    public String relativeVariantUrl(UUID assetId, int width, boolean watermarked, String format) {
        String suffix = watermarked ? "-wm" : "";
        return "/media/" + assetId + "/" + width + suffix + "." + format;
    }

    public void deleteAsset(UUID assetId) throws IOException {
        // Delete original
        Path originalsDir = root.resolve("originals");
        if (Files.exists(originalsDir)) {
            Files.list(originalsDir)
                    .filter(p -> p.getFileName().toString().startsWith(assetId.toString()))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
        // Delete variants
        Path variantsDir = root.resolve("variants").resolve(assetId.toString());
        if (Files.exists(variantsDir)) {
            try (var stream = Files.walk(variantsDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }
}
