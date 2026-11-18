package mdg.mijery.media.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Derives the on-disk directory name for a product from its OAuth client id.
 *
 * <p>Single implementation for every storage path: an asset's original and variants,
 * and the product's logo, all live under the same slug. The client id is the stable
 * key (it cannot be renamed once registered), so the directory is stable too.
 *
 * <p>Strict on purpose. A client id reaches this class from a request parameter, and
 * the result is concatenated into a filesystem path; only lower-case letters, digits
 * and underscores survive, and the final value is re-checked against an allow-list
 * pattern. Anything that could escape the storage root throws instead of being
 * sanitised silently.
 */
public final class TenantPaths {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._~-]{1,100}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9_]{1,64}");

    private TenantPaths() {
    }

    /**
     * @throws IllegalArgumentException when the client id is missing, contains
     *         characters outside the registered-client alphabet, or cannot yield a safe
     *         directory name
     */
    public static String slug(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        String trimmed = clientId.trim();
        if (!CLIENT_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "clientId may only contain letters, digits, '.', '_', '~' and '-'");
        }
        String slug = trimmed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "clientId must contain at least one letter or digit");
        }
        return slug;
    }

    /** Non-throwing variant for request parsing. */
    public static boolean isValid(String clientId) {
        try {
            slug(clientId);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
