package mdg.mijery.media.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/**
 * Local filesystem storage, partitioned per product.
 *
 * <pre>
 * media-storage/{slug}/originals/{assetId}.{ext}
 * media-storage/{slug}/variants/{assetId}/{width}[-wm].webp
 * logo-storage/{slug}/logo.webp
 * logo-storage/{slug}/watermark.webp
 * </pre>
 *
 * <p>Every public method takes a client id and resolves it through
 * {@link TenantPaths}; there is deliberately no unscoped overload. The previous flat
 * layout is gone rather than kept as a fallback: no asset had ever been stored, so
 * there is nothing to be compatible with, and a shared directory would quietly mix
 * tenants.
 *
 * <p>In production, swap for S3-compatible storage by implementing the same API.
 */
@Service
public class StorageService {

    private final Path mediaRoot;
    private final Path logoRoot;

    public StorageService(@Value("${mijery.media.storage-dir}") String storageDir,
                          @Value("${mijery.media.logo-dir}") String logoDir) {
        this.mediaRoot = Paths.get(storageDir).toAbsolutePath().normalize();
        this.logoRoot = Paths.get(logoDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(mediaRoot);
            Files.createDirectories(logoRoot);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create storage directories", e);
        }
    }

    /** Relative key for an asset original, e.g. {@code mdg_chronicles/originals/{id}.png}. */
    public String originalKey(String clientId, UUID assetId, String ext) {
        return TenantPaths.slug(clientId) + "/originals/" + assetId + ext;
    }

    public Path storeOriginal(String clientId, UUID assetId, InputStream data, String ext) throws IOException {
        Path target = resolveKey(mediaRoot, originalKey(clientId, assetId, ext));
        Files.createDirectories(target.getParent());
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public Path storeVariant(String clientId, UUID assetId, int width, String format,
                             boolean watermarked, byte[] data) throws IOException {
        Path target = resolveVariant(clientId, assetId, width, watermarked, format);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return target;
    }

    public Path resolveVariant(String clientId, UUID assetId, int width, boolean watermarked, String format) {
        String slug = TenantPaths.slug(clientId);
        String suffix = watermarked ? "-wm" : "";
        return resolveKey(mediaRoot,
                slug + "/variants/" + assetId + "/" + width + suffix + "." + format);
    }

    public String relativeVariantUrl(UUID assetId, int width, boolean watermarked, String format) {
        String suffix = watermarked ? "-wm" : "";
        return "/media/" + assetId + "/" + width + suffix + "." + format;
    }

    /** Deletes every original and variant belonging to one asset, inside its product. */
    public void deleteAsset(String clientId, UUID assetId) throws IOException {
        String slug = TenantPaths.slug(clientId);
        Path originalsDir = resolveKey(mediaRoot, slug + "/originals");
        if (Files.exists(originalsDir)) {
            try (var stream = Files.list(originalsDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith(assetId.toString()))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
        deleteTree(resolveKey(mediaRoot, slug + "/variants/" + assetId));
    }

    // ── Product logos ───────────────────────────────────────────────────────

    public Path logoDirectory(String clientId) throws IOException {
        Path dir = resolveKey(logoRoot, TenantPaths.slug(clientId));
        Files.createDirectories(dir);
        return dir;
    }

    /** Relative key for a logo file, e.g. {@code mdg_chronicles/logo.webp}. */
    public String logoKey(String clientId, String filename) {
        return TenantPaths.slug(clientId) + "/" + filename;
    }

    public Path resolveLogoKey(String storageKey) {
        return resolveKey(logoRoot, storageKey);
    }

    /** Removes every file of a product's logo directory (used on replace and delete). */
    public void deleteLogoDirectory(String clientId) throws IOException {
        deleteTree(resolveKey(logoRoot, TenantPaths.slug(clientId)));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    /** Resolves a relative key under a root, refusing anything that would escape it. */
    private static Path resolveKey(Path root, String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes its root: " + key);
        }
        return resolved;
    }
}

