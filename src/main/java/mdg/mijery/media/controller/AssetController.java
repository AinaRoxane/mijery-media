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
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final MediaAssetRepository assetRepo;
    private final MediaVariantRepository variantRepo;
    private final StorageService storage;
    private final ImageProcessingService imageProcessor;
    private final SignedUrlService signedUrlService;
    private final long maxUploadBytes;
    private final List<String> allowedMimeTypes;

    public AssetController(
            MediaAssetRepository assetRepo,
            MediaVariantRepository variantRepo,
            StorageService storage,
            ImageProcessingService imageProcessor,
            SignedUrlService signedUrlService,
            @Value("${mijery.media.max-upload-bytes}") long maxUploadBytes,
            @Value("${mijery.media.allowed-mime-types}") String allowedMimeTypesStr) {
        this.assetRepo = assetRepo;
        this.variantRepo = variantRepo;
        this.storage = storage;
        this.imageProcessor = imageProcessor;
        this.signedUrlService = signedUrlService;
        this.maxUploadBytes = maxUploadBytes;
        this.allowedMimeTypes = Arrays.asList(allowedMimeTypesStr.split(","));
    }
}

    @PostMapping
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "visibility", defaultValue = "PUBLIC") String visibility,
            @RequestParam(value = "ownerSub") String ownerSub) {

        if (file.isEmpty() || file.getSize() > maxUploadBytes) {
            return ResponseEntity.badRequest().body("File empty or exceeds max size");
        }

        String mime = file.getContentType();
        if (mime == null || !allowedMimeTypes.contains(mime)) {
            return ResponseEntity.badRequest().body("Unsupported file type: " + mime);
        }

        try {
            MediaAsset asset = MediaAsset.builder()
                    .ownerSub(UUID.fromString(ownerSub))
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
            java.nio.file.Path originalPath = storage.storeOriginal(asset.getId(), file.getInputStream(), ext);
            asset.setOriginalKey(originalPath.toString());

            byte[] cleaned = imageProcessor.reencodeOriginal(originalPath);
            java.nio.file.Files.write(originalPath, cleaned);

            Map<Integer, String> variantUrls = new HashMap<>();
            boolean watermarked = asset.getVisibility() == Visibility.PROTECTED;
            String watermarkedUrl = null;

            for (int width : imageProcessor.getVariantWidths()) {
                byte[] variantData = imageProcessor.generateVariant(originalPath, width);
                storage.storeVariant(asset.getId(), width, "webp", false, variantData);
                String relUrl = storage.relativeVariantUrl(asset.getId(), width, false, "webp");
                variantUrls.put(width, relUrl);

                if (watermarked) {
                    byte[] wmData = imageProcessor.generateWatermarkedVariant(originalPath, width);
                    storage.storeVariant(asset.getId(), width, "webp", true, wmData);
                    if (width == 1080) {
                        watermarkedUrl = storage.relativeVariantUrl(asset.getId(), width, true, "webp");
                    }
                }

                MediaVariant variant = MediaVariant.builder()
                        .assetId(asset.getId()).width(width).format("webp")
                        .watermarked(false).key(relUrl).bytes(variantData.length).build();
                variantRepo.save(variant);

                if (watermarked) {
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
        try {
            storage.deleteAsset(id);
            assetRepo.deleteById(id);
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
