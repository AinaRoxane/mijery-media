package mdg.mijery.media.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * HMAC-based signed URL generation and verification for protected assets.
 * Nginx's secure_link module verifies the signature + expiry before serving.
 */
@Service
public class SignedUrlService {

    private final String secret;
    private final long ttlSeconds;

    public SignedUrlService(
            @Value("${mijery.media.signing-secret}") String secret,
            @Value("${mijery.media.signed-url-ttl}") long ttlSeconds) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Generate a signed URL for a protected asset variant.
     * Format: /media/{assetId}/{width}-wm.webp?md5={hash}&expires={ts}
     */
    public String sign(String path) {
        long expires = Instant.now().getEpochSecond() + ttlSeconds;
        String data = path + expires;
        String hash = hmacMd5(data);
        return path + "?md5=" + hash + "&expires=" + expires;
    }

    public boolean verify(String path, String md5, long expires) {
        if (Instant.now().getEpochSecond() > expires) {
            return false; // expired
        }
        String expected = hmacMd5(path + expires);
        return expected.equals(md5);
    }

    private String hmacMd5(String data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
