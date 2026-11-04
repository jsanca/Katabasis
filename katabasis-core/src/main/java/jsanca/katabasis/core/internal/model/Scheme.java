package jsanca.katabasis.core.internal.model;

import java.util.Locale;

/**
 * URI schemes that the engine can resolve to a download strategy.
 *
 * <p>Only HTTP and HTTPS are supported today.
 *
 * @author jsanca &amp; elo
 */
public enum Scheme {

    /** Hypertext Transfer Protocol. */
    HTTP,

    /** Hypertext Transfer Protocol Secure. */
    HTTPS;

    /**
     * Resolves a URI scheme string to a supported {@link Scheme}.
     *
     * <p>Matching is case-insensitive.
     *
     * @param scheme the URI scheme, for example {@code http}
     * @return the matching scheme
     * @throws IllegalArgumentException if the scheme is not supported
     */
    public static Scheme from(final String scheme) {
        final Scheme resolvedScheme;
        try {

            resolvedScheme = Scheme.valueOf(scheme.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "No download strategy available for scheme: " + scheme, e);
        }

        return resolvedScheme;
    }
}
