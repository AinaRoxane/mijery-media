package mdg.mijery.media.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageServiceTest {

    @TempDir
    Path root;

    private StorageService service() {
        return new StorageService(root.resolve("media").toString(), root.resolve("logo").toString());
    }

    @Test
    void assetsLiveUnderTheirProductDirectory() throws Exception {
        StorageService storage = service();
        UUID assetId = UUID.randomUUID();

        Path original = storage.storeOriginal("mdg-chronicles", assetId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), ".png");

        assertThat(original).isEqualTo(
                root.resolve("media/mdg_chronicles/originals/" + assetId + ".png"));
        assertThat(Files.exists(original)).isTrue();
        assertThat(storage.originalKey("mdg-chronicles", assetId, ".png"))
                .isEqualTo("mdg_chronicles/originals/" + assetId + ".png");
    }

    @Test
    void variantsAreNamespacedByProductAndAsset() {
        UUID assetId = UUID.randomUUID();
        Path plain = service().resolveVariant("mdg-chronicles", assetId, 640, false, "webp");
        Path marked = service().resolveVariant("mdg-chronicles", assetId, 640, true, "webp");

        assertThat(plain).isEqualTo(
                root.resolve("media/mdg_chronicles/variants/" + assetId + "/640.webp"));
        assertThat(marked).isEqualTo(
                root.resolve("media/mdg_chronicles/variants/" + assetId + "/640-wm.webp"));
    }

    @Test
    void deletingAnAssetOnlyTouchesItsProductDirectory() throws Exception {
        StorageService storage = service();
        UUID assetId = UUID.randomUUID();
        Path original = storage.storeOriginal("mdg-chronicles", assetId,
                new ByteArrayInputStream(new byte[]{1}), ".png");
        Path variant = storage.resolveVariant("mdg-chronicles", assetId, 320, false, "webp");
        Files.createDirectories(variant.getParent());
        Files.write(variant, new byte[]{2});

        storage.deleteAsset("mdg-chronicles", assetId);

        assertThat(Files.exists(original)).isFalse();
        assertThat(Files.exists(variant)).isFalse();
    }

    @Test
    void logoTreesAreIsolatedPerProduct() throws Exception {
        StorageService storage = service();
        Path first = storage.logoDirectory("mdg-chronicles").resolve("logo.webp");
        Files.write(first, new byte[]{1});
        Path second = storage.logoDirectory("mdg-chronicles-studio").resolve("logo.webp");
        Files.write(second, new byte[]{2});

        storage.deleteLogoDirectory("mdg-chronicles");

        assertThat(Files.exists(first)).isFalse();
        assertThat(Files.exists(second)).isTrue();
    }

    @Test
    void storageKeysCannotEscapeTheRoot() {
        assertThatThrownBy(() -> new StorageService(root.resolve("media").toString(),
                root.resolve("logo").toString()).resolveLogoKey("../outside.webp"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
