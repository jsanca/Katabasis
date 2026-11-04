package jsanca.katabasis.core.internal.exception;


import jsanca.katabasis.core.api.event.DownloadErrorCode;
import jsanca.katabasis.core.api.model.DownloadInfo;

import java.io.IOException;
import java.util.Objects;

/**
 * Internal signal for a non-successful HTTP response status.
 *
 * <p>Carries the download identity and status code, and maps common statuses to
 * public {@link DownloadErrorCode} values. It is an {@link IOException} so the
 * strategies route it through their generic error handling.
 *
 * @author jsanca &amp; elo
 */
public class HttpDownloadStatusException extends IOException {

    private final jsanca.katabasis.core.api.model.DownloadInfo info;
    private final int statusCode;

    /**
     * Creates an exception for an unexpected HTTP status.
     *
     * @param info identifying information of the download
     * @param statusCode the received HTTP status code
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public HttpDownloadStatusException(final DownloadInfo info,
                                       final int statusCode) {
        super("Unexpected HTTP status code " + statusCode + " for download " + info.downloadId());
        this.info = Objects.requireNonNull(info, "info must not be null");
        this.statusCode = statusCode;
    }

    /**
     * Returns the identifying information of the failed download.
     *
     * @return the download information
     */
    public DownloadInfo info() {
        return info;
    }

    /**
     * Returns the received HTTP status code.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Maps the status code to a public error code.
     *
     * <p>Recognises 400, 401, 403, 404 and 503 explicitly; other 5xx codes map
     * to {@code HTTP_SERVER_ERROR} and everything else to
     * {@code HTTP_INVALID_RESPONSE}.
     *
     * @return the matching error code
     */
    public DownloadErrorCode mapHttpStatusToErrorCode() {
        return switch (this.statusCode) {
            case 400 -> DownloadErrorCode.HTTP_BAD_REQUEST;
            case 401 -> DownloadErrorCode.HTTP_UNAUTHORIZED;
            case 403 -> DownloadErrorCode.HTTP_FORBIDDEN;
            case 404 -> DownloadErrorCode.HTTP_NOT_FOUND;
            case 503 -> DownloadErrorCode.HTTP_SERVICE_UNAVAILABLE;
            default -> {
                if (statusCode >= 500 && statusCode <= 599) {
                    yield DownloadErrorCode.HTTP_SERVER_ERROR;
                }
                yield DownloadErrorCode.HTTP_INVALID_RESPONSE;
            }
        };
    }
}
