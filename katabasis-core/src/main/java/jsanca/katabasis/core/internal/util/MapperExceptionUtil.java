package jsanca.katabasis.core.internal.util;

import jsanca.katabasis.core.api.event.DownloadErrorCode;

import java.io.IOException;

/**
 * Maps execution exceptions to public {@link DownloadErrorCode} values.
 *
 * @author jsanca &amp; elo
 */
public class MapperExceptionUtil {

    /**
     * Maps an exception to the closest public error code.
     *
     * <p>{@link IOException} maps to {@code IO_ERROR}, {@link InterruptedException}
     * maps to {@code INTERRUPTED}, and anything else maps to {@code UNKNOWN}.
     *
     * @param e the exception to classify
     * @return the matching error code
     */
    public static DownloadErrorCode mapExceptionToErrorCode(final Exception e) {
        // todo: on the future create a MapperExceptionUtil to handle this
        if (e instanceof IOException) {
            return DownloadErrorCode.IO_ERROR;
        }

        if (e instanceof InterruptedException) {
            return DownloadErrorCode.INTERRUPTED;
        }

        return DownloadErrorCode.UNKNOWN;
    }
}
