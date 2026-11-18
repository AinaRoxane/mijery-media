package mdg.mijery.media.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantPathsTest {

    @Test
    void clientIdsMapToReadableStableDirectories() {
        assertThat(TenantPaths.slug("mdg-chronicles")).isEqualTo("mdg_chronicles");
        assertThat(TenantPaths.slug("mdg-chronicles-studio")).isEqualTo("mdg_chronicles_studio");
        assertThat(TenantPaths.slug("Chronicles.MG")).isEqualTo("chronicles_mg");
        assertThat(TenantPaths.slug("  mdg-chronicles  ")).isEqualTo("mdg_chronicles");
    }

    @Test
    void hostileClientIdsAreRejectedRatherThanSanitisedIntoCollisions() {
        assertThatThrownBy(() -> TenantPaths.slug(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.slug(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.slug("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.slug("mdg/chronicles"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.slug(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.slug("-"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validityCheckMatchesTheSlugRule() {
        assertThat(TenantPaths.isValid("mdg-chronicles")).isTrue();
        assertThat(TenantPaths.isValid("../etc")).isFalse();
        assertThat(TenantPaths.isValid(" ")).isFalse();
    }
}
