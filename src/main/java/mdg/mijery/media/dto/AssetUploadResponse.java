package mdg.mijery.media.dto;

import java.util.Map;
import java.util.UUID;

public record AssetUploadResponse(
    UUID assetId,
    String mime,
    long bytes,
    Map<Integer, String> variants,      // width -> relative URL
    String watermarkedVariant            // relative URL or null
) {}
