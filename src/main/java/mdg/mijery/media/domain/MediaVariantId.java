package mdg.mijery.media.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class MediaVariantId implements Serializable {
    private UUID assetId;
    private int width;
    private String format;
    private boolean watermarked;

    public MediaVariantId() {}

    public MediaVariantId(UUID assetId, int width, String format, boolean watermarked) {
        this.assetId = assetId;
        this.width = width;
        this.format = format;
        this.watermarked = watermarked;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MediaVariantId that)) return false;
        return width == that.width && watermarked == that.watermarked
                && Objects.equals(assetId, that.assetId) && Objects.equals(format, that.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetId, width, format, watermarked);
    }
}
