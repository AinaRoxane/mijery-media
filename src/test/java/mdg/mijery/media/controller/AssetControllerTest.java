package mdg.mijery.media.controller;

import mdg.mijery.media.domain.MediaAsset;
import mdg.mijery.media.domain.MediaVariant;
import mdg.mijery.media.dto.AssetUploadResponse;
import mdg.mijery.media.repository.MediaAssetRepository;
import mdg.mijery.media.repository.MediaVariantRepository;
import mdg.mijery.media.service.ImageProcessingService;
import mdg.mijery.media.service.ProductLogoService;
import mdg.mijery.media.service.SignedUrlService;
import mdg.mijery.media.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetControllerTest {

    @TempDir
    Path tempDir;

    private MediaAssetRepository assetRepo;
    private MediaVariantRepository variantRepo;
    private StorageService storage;
    private ImageProcessingService imageProcessor;
    private ProductLogoService productLogoService;
    private AssetController controller;

    @BeforeEach
    void setUp() throws Exception {
        assetRepo = mock(MediaAssetRepository.class);
        variantRepo = mock(MediaVariantRepository.class);
        storage = mock(StorageService.class);
        imageProcessor = mock(ImageProcessingService.class);
        productLogoService = mock(ProductLogoService.class);
        controller = new AssetController(assetRepo, variantRepo, storage, imageProcessor,
                productLogoService, mock(SignedUrlService.class), 10_000L, "image/png,image/jpeg");

        when(assetRepo.save(any(MediaAsset.class))).thenAnswer(invocation -> {
            MediaAsset asset = invocation.getArgument(0);
            if (asset.getId() == null) {
                asset.setId(UUID.randomUUID());
            }
            return asset;
        });
        when(imageProcessor.getVariantWidths()).thenReturn(List.of(320));
        when(imageProcessor.reencodeOriginal(any())).thenReturn(new byte[]{9});
        when(imageProcessor.generateVariant(any(), anyInt())).thenReturn(new byte[]{1});
        when(storage.storeOriginal(anyString(), any(), any(), anyString()))
                .thenReturn(tempDir.resolve("original.png"));
        when(storage.originalKey(anyString(), any(), anyString()))
                .thenReturn("mdg_chronicles/originals/asset.png");
        when(storage.relativeVariantUrl(any(), anyInt(), anyBoolean(), anyString()))
                .thenReturn("/media/asset/320.webp");
        when(productLogoService.watermarkFor(anyString())).thenReturn(Optional.empty());
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void anUploadWithoutAClientIdIsRejectedBeforeAnythingIsWritten() throws Exception {
        ResponseEntity<?> response = controller.upload(png(), "PUBLIC",
                UUID.randomUUID().toString(), "  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(assetRepo, never()).save(any(MediaAsset.class));
        verify(storage, never()).storeOriginal(anyString(), any(), any(), anyString());
    }

    @Test
    void aValidUploadIsScopedToTheCallersProduct() throws Exception {
        ResponseEntity<?> response = controller.upload(png(), "PUBLIC",
                UUID.randomUUID().toString(), "mdg-chronicles");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(AssetUploadResponse.class);
        verify(assetRepo, times(2)).save(any(MediaAsset.class));
        verify(storage).storeOriginal(anyString(), any(), any(), anyString());
        verify(storage).storeVariant(anyString(), any(), anyInt(), anyString(), anyBoolean(), any());
        verify(variantRepo).save(any(MediaVariant.class));
        verify(imageProcessor, never()).generateWatermarkedVariant(any(), anyInt(), any());
    }

    @Test
    void aProtectedUploadWithoutAProductLogoGetsNoWatermarkedVariant() throws Exception {
        ResponseEntity<?> response = controller.upload(png(), "PROTECTED",
                UUID.randomUUID().toString(), "mdg-chronicles");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AssetUploadResponse body = (AssetUploadResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.watermarkedVariant()).isNull();
        verify(imageProcessor, never()).generateWatermarkedVariant(any(), anyInt(), any());
    }
}
