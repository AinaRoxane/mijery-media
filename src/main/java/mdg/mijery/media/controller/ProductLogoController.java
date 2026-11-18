package mdg.mijery.media.controller;

import lombok.RequiredArgsConstructor;
import mdg.mijery.media.service.ProductLogoService;
import mdg.mijery.media.service.ProductLogoService.LogoDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Product logo endpoints.
 *
 * <p>Writes are called by the SSO tenant console over the service token; the public
 * {@code GET /logo/{clientId}} exists so a logo can be displayed directly. One logo
 * per product — {@code PUT} replaces the previous file and row.
 */
@RestController
@RequiredArgsConstructor
public class ProductLogoController {

    private static final int MAX_BATCH = 50;

    private final ProductLogoService logoService;

    @Value("${mijery.media.max-logo-bytes}")
    private long maxLogoBytes;

    @PutMapping(value = "/api/products/{clientId}/logo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable String clientId,
                                    @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Choose an image to upload"));
        }
        if (file.getSize() > maxLogoBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("success", false,
                            "message", "Logo exceeds the maximum of " + maxLogoBytes + " bytes"));
        }
        try {
            return ResponseEntity.ok(logoService.save(clientId, file));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Logo upload failed: " + ex.getMessage()));
        }
    }

    @GetMapping("/api/products/{clientId}/logo")
    public ResponseEntity<?> metadata(@PathVariable String clientId) {
        try {
            return logoService.find(clientId)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    /** Batch lookup for one dashboard page; missing products are simply absent. */
    @GetMapping("/api/products/logos")
    public ResponseEntity<?> batch(@RequestParam(name = "clientIds", required = false) String clientIds) {
        List<String> ids = clientIds == null ? List.of()
                : Arrays.stream(clientIds.split(","))
                        .map(String::trim)
                        .filter(id -> !id.isEmpty())
                        .distinct()
                        .toList();
        if (ids.size() > MAX_BATCH) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "At most " + MAX_BATCH + " client ids per request"));
        }
        return ResponseEntity.ok(logoService.findAll(ids));
    }

    @DeleteMapping("/api/products/{clientId}/logo")
    public ResponseEntity<?> delete(@PathVariable String clientId) {
        try {
            return logoService.delete(clientId)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Logo delete failed: " + ex.getMessage()));
        }
    }

    /** Public logo bytes, versioned by the stored hash. */
    @GetMapping("/logo/{clientId}")
    public ResponseEntity<?> serve(@PathVariable String clientId,
                                   @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        Optional<LogoDescriptor> descriptor = logoService.find(clientId);
        Optional<Path> file = logoService.logoFile(clientId);
        if (descriptor.isEmpty() || file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LogoDescriptor logo = descriptor.get();
        String etag = "\"" + logo.hash() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        try {
            return ResponseEntity.ok()
                    .eTag(etag)
                    .contentType(MediaType.parseMediaType(logo.mime()))
                    .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                    .body(Files.readAllBytes(file.get()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
