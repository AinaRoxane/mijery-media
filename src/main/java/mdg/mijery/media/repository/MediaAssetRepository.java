package mdg.mijery.media.repository;

import mdg.mijery.media.domain.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
}
