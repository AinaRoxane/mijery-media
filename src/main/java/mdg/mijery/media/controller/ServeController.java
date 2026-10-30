package mdg.mijery.media.controller;

import mdg.mijery.media.domain.MediaAsset;
import mdg.mijery.media.domain.MediaVariant;
import mdg.mijery.media.repository.MediaAssetRepository;
import mdg.mijery.media.repository.MediaVariantRepository;
import mdg.mijery.media.service.SignedUrlService;
import mdg.mijery.media.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Serves media variants. For public assets, serves directly with cache headers.
 * For protected assets, verifies the signed URL (HMAC + expiry) before serving.
 */
@RestController
public class ServeController {

    private final MediaAssetRepository assetRepo;
    private final MediaVariantRepository variantRepo;
    private final StorageService storage;
    private final SignedUrlService signedUrlService;
    private final boolean useXAccelRedirect;
    private final String xAccelPrefix;

    public ServeController(
            MediaAssetRepository assetRepo,
            MediaVariantRepository variantRepo,
            StorageService storage,
            SignedUrlService signedUrlService,
            @Value("${mijery.media.use-x-accel-redirect:false}") boolean useXAccelRedirect,
            @Value("${mijery.media.x-accel-prefix:/media-internal}") String xAccelPrefix) {
        this.assetRepo = assetRepo;
        this.variantRepo = variantRepo;
        this.storage = storage;
        this.signedUrlService = signedUrlService;
        this.useXAccelRedirect = useXAccelRedirect;
        this.xAccelPrefix = xAccelPrefix;
    }

    @GetMapping("/media/{assetId}/{filename}")
    public ResponseEntity<?> serve(
            @PathVariable UUID assetId,
            @PathVariable String filename,
            @RequestParam(value = "md5", required = false) String md5,
            @RequestParam(value = "expires", required = false) Long expires) {

        MediaAsset asset = assetRepo.findById(assetId).orElse(null);
        if (asset == null || asset.getStatus() != mdg.mijery.media.domain.AssetStatus.READY) {
            return ResponseEntity.notFound().build();
        }

        // Protected assets require signed URL verification
        if (asset.getVisibility() == mdg.mijery.media.domain.Visibility.PROTECTED) {
            if (md5 == null || expires == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Signed URL required");
            }
            String path = "/media/" + assetId + "/" + filename;
            if (!signedUrlService.verify(path, md5, expires)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid or expired signature");
            }
        }

        // Resolve variant path
        boolean watermarked = filename.contains("-wm");
        int width = parseWidth(filename);
        String format = filename.endsWith(".webp") ? "webp" : "jpg";

        Path filePath = storage.resolveVariant(assetId, width, watermarked, format);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            if (useXAccelRedirect) {
                // Let Nginx serve the file
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Accel-Redirect", xAccelPrefix + "/" + assetId + "/" + filename);
                return new ResponseEntity<>(headers, HttpStatus.OK);
            }

            byte[] data = Files.readAllBytes(filePath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(asset.getMime().replace("image/", "image/")));
            if (asset.getVisibility() == mdg.mijery.media.domain.Visibility.PUBLIC) {
                // Immutable cache for public assets (content-hash in key)
                headers.setCacheControl(CacheControl.maxAge(365, java.util.concurrent.TimeUnit.DAYS).cachePublic().immutable());
            } else {
                headers.setCacheControl(CacheControl.noStore());
            }
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private int parseWidth(String filename) {
        String num = filename.replaceAll("[^0-9].*", "");
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
