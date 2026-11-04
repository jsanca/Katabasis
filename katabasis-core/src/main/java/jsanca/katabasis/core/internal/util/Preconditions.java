package jsanca.katabasis.core.internal.util;

import java.util.function.Supplier;

/**
 * Small validation helpers for internal argument and state checks.
 *
 * @author jsanca &amp; elo
 */
public final class Preconditions {

    private Preconditions() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Requires {@code condition} to be {@code true}.
     *
     * @param condition the condition that must hold
     * @param message the message used when the condition is false
     * @throws IllegalArgumentException if {@code condition} is {@code false}
     */
    public static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Requires {@code condition} to be {@code true}, building the message lazily.
     *
     * @param condition the condition that must hold
     * @param message supplier evaluated only when the condition is false
     * @throws IllegalArgumentException if {@code condition} is {@code false}
     */
    public static void check(final boolean condition, final Supplier<String> message) {
        if (!condition) {
            throw new IllegalArgumentException(message.get());
        }
    }

    /**
     * Requires {@code condition} to be {@code false}.
     *
     * @param condition the condition that must not hold
     * @param message the message used when the condition is true
     * @throws IllegalArgumentException if {@code condition} is {@code true}
     */
    public static void reject(final boolean condition, final String message) {
        if (condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Requires {@code condition} to be {@code false}, building the message lazily.
     *
     * @param condition the condition that must not hold
     * @param message supplier evaluated only when the condition is true
     * @throws IllegalArgumentException if {@code condition} is {@code true}
     */
    public static void reject(final boolean condition, final Supplier<String> message) {
        if (condition) {
            throw new IllegalArgumentException(message.get());
        }
    }
}