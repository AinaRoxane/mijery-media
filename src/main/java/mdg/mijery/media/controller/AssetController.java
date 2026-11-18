package mdg.mijery.media.controller;

import mdg.mijery.media.domain.*;
import mdg.mijery.media.dto.AssetUploadResponse;
import mdg.mijery.media.repository.MediaAssetRepository;
import mdg.mijery.media.repository.MediaVariantRepository;
import mdg.mijery.media.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final MediaAssetRepository assetRepo;
    private final MediaVariantRepository variantRepo;
    private final StorageService storage;
    private final ImageProcessingService imageProcessor;
    private final ProductLogoService productLogoService;
    private final SignedUrlService signedUrlService;
    private final long maxUploadBytes;
    private final List<String> allowedMimeTypes;

    public AssetController(MediaAssetRepository assetRepo,
                           MediaVariantRepository variantRepo,
                           StorageService storage,
                           ImageProcessingService imageProcessor,
                           ProductLogoService productLogoService,
                           SignedUrlService signedUrlService,
                           @Value("${mijery.media.max-upload-bytes}") long maxUploadBytes,
                           @Value("${mijery.media.allowed-mime-types}") String allowedMimeTypesStr) {
        this.assetRepo = assetRepo;
        this.variantRepo = variantRepo;
        this.storage = storage;
        this.imageProcessor = imageProcessor;
        this.productLogoService = productLogoService;
        this.signedUrlService = signedUrlService;
        this.maxUploadBytes = maxUploadBytes;
        this.allowedMimeTypes = Arrays.asList(allowedMimeTypesStr.split(","));
    }

    @PostMapping
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "visibility", defaultValue = "PUBLIC") String visibility,
            @RequestParam(value = "ownerSub") String ownerSub,
            @RequestParam(value = "clientId", required = false) String clientId) {

        // Fail closed: storage is partitioned per product, so an upload that cannot be
        // attributed is refused before a row or a file is created.
        final String product;
        try {
            TenantPaths.slug(clientId);
            product = clientId.trim();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }

        if (file.isEmpty() || file.getSize() > maxUploadBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("success", false, "message", "File too large. Maximum 10 MB allowed."));
        }

        String mime = file.getContentType();
        if (mime == null || !allowedMimeTypes.contains(mime)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Unsupported file type: " + mime));
        }

        try {
            MediaAsset asset = MediaAsset.builder()
                    .ownerSub(UUID.fromString(ownerSub))
                    .clientId(product)
                    .kind(AssetKind.IMAGE)
                    .visibility(Visibility.valueOf(visibility.toUpperCase()))
                    .originalKey("")
                    .mime(mime)
                    .bytes(file.getSize())
                    .hash(computeHash(file.getInputStream()))
                    .status(AssetStatus.PENDING)
                    .build();
            asset = assetRepo.save(asset);

            String ext = mimeToExt(mime);
            java.nio.file.Path originalPath = storage.storeOriginal(product, asset.getId(), file.getInputStream(), ext);
            asset.setOriginalKey(storage.originalKey(product, asset.getId(), ext));

            byte[] cleaned = imageProcessor.reencodeOriginal(originalPath);
            java.nio.file.Files.write(originalPath, cleaned);

            Map<Integer, String> variantUrls = new HashMap<>();
            Path watermark = productLogoService.watermarkFor(product).orElse(null);
            // No logo means no watermarked variant — never a plain copy labelled as one.
            boolean watermarked = asset.getVisibility() == Visibility.PROTECTED && watermark != null;
            String watermarkedUrl = null;

            for (int width : imageProcessor.getVariantWidths()) {
                byte[] variantData = imageProcessor.generateVariant(originalPath, width);
                storage.storeVariant(product, asset.getId(), width, "webp", false, variantData);
                String relUrl = storage.relativeVariantUrl(asset.getId(), width, false, "webp");
                variantUrls.put(width, relUrl);

                byte[] wmData = watermarked
                        ? imageProcessor.generateWatermarkedVariant(originalPath, width, watermark)
                        : null;
                if (wmData != null) {
                    storage.storeVariant(product, asset.getId(), width, "webp", true, wmData);
                    if (width == 1080 || watermarkedUrl == null) {
                        watermarkedUrl = storage.relativeVariantUrl(asset.getId(), width, true, "webp");
                    }
                }

                MediaVariant variant = MediaVariant.builder()
                        .assetId(asset.getId()).width(width).format("webp")
                        .watermarked(false).key(relUrl).bytes(variantData.length).build();
                variantRepo.save(variant);

                if (wmData != null) {
                    MediaVariant wmVariant = MediaVariant.builder()
                            .assetId(asset.getId()).width(width).format("webp")
                            .watermarked(true)
                            .key(storage.relativeVariantUrl(asset.getId(), width, true, "webp"))
                            .bytes(wmData.length).build();
                    variantRepo.save(wmVariant);
                }
            }

            asset.setStatus(AssetStatus.READY);
            assetRepo.save(asset);

            return ResponseEntity.ok(new AssetUploadResponse(
                    asset.getId(), mime, file.getSize(), variantUrls, watermarkedUrl));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Upload failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        MediaAsset asset = assetRepo.findById(id).orElse(null);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            storage.deleteAsset(asset.getClientId(), id);
            assetRepo.delete(asset);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Delete failed: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaAsset> getMetadata(@PathVariable UUID id) {
        return assetRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private String computeHash(InputStream is) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String mimeToExt(String mime) {
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".bin";
        };
    }
}
