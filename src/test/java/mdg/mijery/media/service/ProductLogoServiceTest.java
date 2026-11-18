package mdg.mijery.media.service;

import mdg.mijery.media.domain.ProductLogo;
import mdg.mijery.media.repository.ProductLogoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductLogoServiceTest {

    @TempDir
    Path root;

    private ProductLogoRepository repository;
    private ImageProcessingService imageProcessor;
    private ProductLogoService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductLogoRepository.class);
        imageProcessor = mock(ImageProcessingService.class);
        StorageService storage = new StorageService(
                root.resolve("media").toString(), root.resolve("logo").toString());
        service = new ProductLogoService(storage, imageProcessor, repository);
        ReflectionTestUtils.setField(service, "maxLogoBytes", 1024L);
        ReflectionTestUtils.setField(service, "watermarkOpacity", 35);
    }

    @Test
    void aLogoIsStoredInsideItsProductDirectory() throws Exception {
        // Without vips the service falls back to the uploaded bytes and the original
        // extension; that path must still produce a row and a file.
        when(imageProcessor.normalizeLogo(any(), any())).thenReturn(false);
        when(imageProcessor.renderWatermarkOverlay(any(), any(), anyInt())).thenReturn(false);
        when(imageProcessor.imageSize(any())).thenReturn(new int[]{120, 40});
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png",
                "png-bytes".getBytes(StandardCharsets.UTF_8));

        ProductLogoService.LogoDescriptor descriptor = service.save("mdg-chronicles", file);

        Path stored = root.resolve("logo/mdg_chronicles/logo.png");
        assertThat(Files.exists(stored)).isTrue();
        assertThat(descriptor.clientId()).isEqualTo("mdg-chronicles");
        assertThat(descriptor.hash()).hasSize(64);
        assertThat(descriptor.width()).isEqualTo(120);
        verify(repository).save(any(ProductLogo.class));
    }

    @Test
    void aSecondUploadReplacesTheFirst() throws Exception {
        when(imageProcessor.normalizeLogo(any(), any())).thenReturn(false);
        when(imageProcessor.renderWatermarkOverlay(any(), any(), anyInt())).thenReturn(false);
        when(imageProcessor.imageSize(any())).thenReturn(new int[]{1, 1});

        service.save("mdg-chronicles", new MockMultipartFile(
                "file", "old.png", "image/png", new byte[]{1}));
        service.save("mdg-chronicles", new MockMultipartFile(
                "file", "new.webp", "image/webp", new byte[]{2}));

        assertThat(Files.exists(root.resolve("logo/mdg_chronicles/logo.png"))).isFalse();
        assertThat(Files.exists(root.resolve("logo/mdg_chronicles/logo.webp"))).isTrue();
    }

    @Test
    void anUploadWithoutAClientIdIsRefused() {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png",
                new byte[]{1});

        assertThatThrownBy(() -> service.save(null, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientId is required");
        assertThatThrownBy(() -> service.save("../etc", file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedOrUnsupportedLogosAreRefused() {
        MockMultipartFile tooLarge = new MockMultipartFile("file", "logo.png", "image/png",
                new byte[2048]);
        assertThatThrownBy(() -> service.save("mdg-chronicles", tooLarge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");

        MockMultipartFile svg = new MockMultipartFile("file", "logo.svg", "image/svg+xml",
                new byte[]{1});
        assertThatThrownBy(() -> service.save("mdg-chronicles", svg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported logo type");
    }

    @Test
    void deletingALogoRemovesItsFilesAndRow() throws Exception {
        when(imageProcessor.normalizeLogo(any(), any())).thenReturn(false);
        when(imageProcessor.renderWatermarkOverlay(any(), any(), anyInt())).thenReturn(false);
        when(imageProcessor.imageSize(any())).thenReturn(new int[]{1, 1});
        service.save("mdg-chronicles", new MockMultipartFile(
                "file", "logo.png", "image/png", new byte[]{1}));
        ProductLogo row = ProductLogo.builder()
                .clientId("mdg-chronicles")
                .storageKey("mdg_chronicles/logo.png")
                .build();
        when(repository.findById("mdg-chronicles")).thenReturn(Optional.of(row));

        assertThat(service.delete("mdg-chronicles")).isTrue();

        assertThat(Files.exists(root.resolve("logo/mdg_chronicles"))).isFalse();
        verify(repository).deleteById("mdg-chronicles");
    }

    @Test
    void batchLookupSkipsInvalidIdsAndMissingFiles() {
        ProductLogo present = ProductLogo.builder()
                .clientId("mdg-chronicles").storageKey("mdg_chronicles/logo.webp").build();
        when(repository.findAllById(any())).thenReturn(List.of(present));

        assertThat(service.findAll(List.of("mdg-chronicles", "../etc"))).isEmpty();
        verify(repository).findAllById(List.of("mdg-chronicles"));
    }
}
