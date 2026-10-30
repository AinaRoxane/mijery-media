package mdg.mijery.media.controller;

import mdg.mijery.media.domain.MediaAsset;
import mdg.mijery.media.domain.MediaVariant;
import mdg.mijery.media.repository.MediaAssetRepository;
import mdg.mijery.media.repository.MediaVariantRepository;
import mdg.mijery.media.service.SignedUrlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Issues signed URLs for protected assets. Called by product APIs
 * (e.g. chronicles-api) after entitlement check.
 */
@RestController
@RequestMapping("/api/assets")
public class SignedUrlController {

    private final MediaAssetRepository assetRepo;
    private final MediaVariantRepository variantRepo;
    private final SignedUrlService signedUrlService;

    public SignedUrlController(
            MediaAssetRepository assetRepo,
            MediaVariantRepository variantRepo,
            SignedUrlService signedUrlService) {
        this.assetRepo = assetRepo;
        this.variantRepo = variantRepo;
        this.signedUrlService = signedUrlService;
    }

    /**
     * Issue a signed URL for a protected asset variant.
     * Query params: width (default 1080), watermarked (default true for protected).
     */
    @GetMapping("/{id}/signed-url")
    public ResponseEntity<?> issueSignedUrl(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1080") int width,
            @RequestParam(defaultValue = "true") boolean watermarked) {

        MediaAsset asset = assetRepo.findById(id).orElse(null);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        if (asset.getVisibility() != mdg.mijery.media.domain.Visibility.PROTECTED) {
            // Public assets don't need signed URLs
            String url = "/media/" + id + "/" + width + (watermarked ? "-wm" : "") + ".webp";
            return ResponseEntity.ok(new SignedUrlResponse(url, null, 0));
        }

        String filename = width + (watermarked ? "-wm" : "") + ".webp";
        String path = "/media/" + id + "/" + filename;
        String signedUrl = signedUrlService.sign(path);

        return ResponseEntity.ok(new SignedUrlResponse(signedUrl, null, 600));
    }

    public record SignedUrlResponse(String url, String md5, long expiresIn) {}
}
