package mdg.mijery.media.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A media asset (image, audio, etc.) owned by a MIJERY ID subject.
 *
 * Products store the asset ID, never a URL. URLs are resolved at serve time
 * by mijery-media so storage backend or domain can change without touching data.
 */
@Entity
@Table(name = "media_asset")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** MIJERY ID subject (stable UUID) — the owner. */
    @Column(name = "owner_sub", nullable = false)
    private UUID ownerSub;

    /**
     * The product (OAuth client id) this asset belongs to, e.g. {@code mdg-chronicles}.
     *
     * <p>Mandatory: storage is partitioned per product, and the product's logo is the
     * watermark applied to its protected derivatives. Kept as the canonical client id
     * rather than the directory slug so the row stays meaningful if the slug rule
     * changes; the directory is always derived through
     * {@link mdg.mijery.media.service.TenantPaths}.
     */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Kind of asset: image, audio, video, etc. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssetKind kind;

    /** Visibility level. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Visibility visibility;

    /** Storage key for the original (private, never served directly). */
    @Column(name = "original_key", nullable = false, length = 512)
    private String originalKey;

    /** Detected MIME type (from real file content, not extension). */
    @Column(nullable = false, length = 128)
    private String mime;

    /** File size in bytes. */
    @Column(nullable = false)
    private long bytes;

    /** SHA-256 hash of the original file. */
    @Column(nullable = false, length = 64)
    private String hash;

    /** Lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssetStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = AssetStatus.READY;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
