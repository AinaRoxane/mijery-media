package mdg.mijery.media.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mdg.mijery.media.domain.ProductLogo;
import mdg.mijery.media.repository.ProductLogoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores one brand logo per product and derives the watermark overlay from it.
 *
 * <p>A product has exactly one logo: a second upload replaces the first, files and
 * row together. The stored logo is normalized to WebP (when libvips is available) and
 * a separate {@code watermark.webp} is pre-rendered with its alpha channel at
 * {@code mijery.media.watermark-opacity}; compositing an asset then only resizes and
 * positions that overlay.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductLogoService {

    private static final List<String> ALLOWED_MIME =
            List.of("image/png", "image/jpeg", "image/webp");

    private final StorageService storage;
    private final ImageProcessingService imageProcessor;
    private final ProductLogoRepository repository;

    @Value("${mijery.media.max-logo-bytes}")
    private long maxLogoBytes;

    @Value("${mijery.media.watermark-opacity}")
    private int watermarkOpacity;

    /** Whether this product already has a logo, as the tenant console needs to know. */
    public record LogoDescriptor(
            String clientId,
            String url,
            String mime,
            long bytes,
            int width,
            int height,
            String hash,
            String updatedAt) {
    }

    public LogoDescriptor save(String clientId, MultipartFile file)
            throws IOException, InterruptedException {
        String key = requireClientId(clientId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an image to upload");
        }
        if (file.getSize() > maxLogoBytes) {
            throw new IllegalArgumentException("Logo exceeds the maximum of " + maxLogoBytes + " bytes");
        }
        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            throw new IllegalArgumentException("Unsupported logo type: " + mime);
        }

        Path dir = storage.logoDirectory(key);
        Path upload = dir.resolve(".upload-" + UUID.randomUUID() + extensionFor(mime));
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, upload, StandardCopyOption.REPLACE_EXISTING);
            }

            String filename;
            String storedMime;
            Path webp = dir.resolve("logo.webp");
            boolean normalized = false;
            try {
                normalized = imageProcessor.normalizeLogo(upload, webp);
            } catch (Exception ex) {
                log.warn("Logo normalization failed for '{}': {}", key, ex.getMessage());
            }
            if (normalized) {
                filename = "logo.webp";
                storedMime = "image/webp";
            } else {
                filename = "logo" + extensionFor(mime);
                storedMime = mime;
                Files.copy(upload, dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            }
            deleteOtherFiles(dir, filename);

            Path stored = dir.resolve(filename);
            Path watermark = dir.resolve("watermark.webp");
            Files.deleteIfExists(watermark);
            try {
                imageProcessor.renderWatermarkOverlay(stored, watermark, watermarkOpacity);
            } catch (Exception ex) {
                log.warn("Watermark overlay rendering failed for '{}': {}", key, ex.getMessage());
                Files.deleteIfExists(watermark);
            }

            byte[] bytes = Files.readAllBytes(stored);
            int[] size = imageProcessor.imageSize(stored);

            ProductLogo row = ProductLogo.builder()
                    .clientId(key)
                    .storageKey(storage.logoKey(key, filename))
                    .mime(storedMime)
                    .bytes(bytes.length)
                    .width(size[0])
                    .height(size[1])
                    .hash(sha256(bytes))
                    .updatedAt(Instant.now())
                    .build();
            repository.save(row);
            return descriptor(row);
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    public Optional<LogoDescriptor> find(String clientId) {
        String key;
        try {
            key = requireClientId(clientId);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return repository.findById(key)
                .filter(row -> Files.exists(storage.resolveLogoKey(row.getStorageKey())))
                .map(this::descriptor);
    }

    public Map<String, LogoDescriptor> findAll(Collection<String> clientIds) {
        Map<String, LogoDescriptor> byClient = new LinkedHashMap<>();
        List<String> keys = clientIds.stream()
                .filter(id -> id != null && TenantPaths.isValid(id))
                .map(String::trim)
                .distinct()
                .toList();
        for (ProductLogo row : repository.findAllById(keys)) {
            if (Files.exists(storage.resolveLogoKey(row.getStorageKey()))) {
                byClient.put(row.getClientId(), descriptor(row));
            }
        }
        return byClient;
    }

    /** Absolute path of the stored display logo, or empty when this product has none. */
    public Optional<Path> logoFile(String clientId) {
        return repository.findById(requireClientId(clientId))
                .map(row -> storage.resolveLogoKey(row.getStorageKey()))
                .filter(Files::exists);
    }

    /** The pre-rendered overlay, used when compositing protected variants. */
    public Optional<Path> watermarkFor(String clientId) {
        try {
            return repository.findById(requireClientId(clientId))
                    .map(row -> storage.resolveLogoKey(row.getStorageKey()).getParent()
                            .resolve("watermark.webp"))
                    .filter(Files::exists);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean delete(String clientId) throws IOException {
        String key = requireClientId(clientId);
        if (repository.findById(key).isEmpty()) {
            return false;
        }
        storage.deleteLogoDirectory(key);
        repository.deleteById(key);
        return true;
    }

    private LogoDescriptor descriptor(ProductLogo row) {
        return new LogoDescriptor(row.getClientId(),
                "/logo/" + row.getClientId() + "?v=" + row.getHash(),
                row.getMime(), row.getBytes(), row.getWidth(), row.getHeight(),
                row.getHash(), row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
    }

    private static String requireClientId(String clientId) {
        TenantPaths.slug(clientId);
        return clientId == null ? null : clientId.trim();
    }

    private static String extensionFor(String mime) {
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }

    private static void deleteOtherFiles(Path dir, String keepFilename) throws IOException {
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(keepFilename))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static String sha256(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
