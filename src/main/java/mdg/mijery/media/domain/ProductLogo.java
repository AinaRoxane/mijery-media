package mdg.mijery.media.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A product's brand logo, which is also the watermark applied to that product's
 * protected media.
 *
 * <p>One row per product: the logo is replaced in place when a new one is uploaded.
 * {@code storageKey} is relative to the logo root (never an absolute path), so the
 * root can be relocated without rewriting rows.
 */
@Entity
@Table(name = "product_logo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductLogo {

    /** The product's OAuth client id — the primary key. */
    @Id
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Relative key, e.g. {@code mdg_chronicles/logo.webp}. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(nullable = false, length = 128)
    private String mime;

    @Column(nullable = false)
    private long bytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    /** SHA-256 of the stored logo — used as a cache-busting version. */
    @Column(nullable = false, length = 64)
    private String hash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
