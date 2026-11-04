package jsanca.katabasis.core.internal.util;

import java.util.function.Supplier;

public final class Preconditions {

    private Preconditions() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void check(final boolean condition, final Supplier<String> message) {
        if (!condition) {
            throw new IllegalArgumentException(message.get());
        }
    }

    public static void reject(final boolean condition, final String message) {
        if (condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void reject(final boolean condition, final Supplier<String> message) {
        if (condition) {
            throw new IllegalArgumentException(message.get());
        }
    }
}