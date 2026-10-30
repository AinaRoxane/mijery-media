package mdg.mijery.media.repository;

import mdg.mijery.media.domain.MediaVariant;
import mdg.mijery.media.domain.MediaVariantId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface MediaVariantRepository extends JpaRepository<MediaVariant, MediaVariantId> {
    List<MediaVariant> findByAssetId(UUID assetId);
    List<MediaVariant> findByAssetIdAndWatermarked(UUID assetId, boolean watermarked);
}
