package jsanca.katabasis.core.internal.util;

/**
 * Utility class for working with byte sizes (e.g., KB, MB, GB).
 * @author jsanca
 */
public final class ByteSize {

    public static final long KB = 1024L;
    public static final long MB = 1024 * KB;
    public static final long GB = 1024 * MB;

    public static final long TWO_MB   = 2 * MB;
    public static final long THREE_MB = 3 * MB;
    public static final long FOUR_MB  = 4 * MB;
    public static final long FIVE_MB  = 5 * MB;
    public static final long TEN_MB   = 10 * MB;

    private ByteSize() {
        throw new UnsupportedOperationException("Utility class");
    }
}
