package mdg.mijery.media.domain;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/**
 * A pre-generated variant of a media asset (e.g. 640px WebP, watermarked).
 */
@Entity
@Table(name = "media_variant")
@IdClass(MediaVariantId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaVariant {

    @Id
    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Id
    @Column(nullable = false)
    private int width;

    @Id
    @Column(nullable = false, length = 16)
    private String format;

    @Id
    @Column(nullable = false)
    private boolean watermarked;

    /** Storage key for this variant. */
    @Column(nullable = false, length = 512)
    private String key;

    /** File size in bytes. */
    @Column(nullable = false)
    private long bytes;
}
