package jsanca.katabasis.cli;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Formats structured error lines and batch summaries for stderr.
 *
 * <h2>Error line grammar</h2>
 * <pre>
 *   ERROR code=CODE [downloadId=ID] [source=URI] message="ESCAPED_MSG"
 * </pre>
 * Optional fields are omitted when null or blank.
 *
 * <h2>Batch summary grammar</h2>
 * <pre>
 *   SUMMARY total=N completed=N failed=N cancelled=N rejected=N
 * </pre>
 *
 * <h2>Message escaping</h2>
 * The {@code message} value is always double-quoted.
 * Inside the quotes: {@code \} → {@code \\}, {@code "} → {@code \"},
 * CR → {@code \r}, LF → {@code \n}, TAB → {@code \t}.
 *
 * <h2>Credential safety</h2>
 * {@link URI} values are sanitized: the {@code userinfo} component (e.g. {@code user:password@})
 * is stripped before the URI appears in any output field.
 */
final class CliErrorFormatter {

    private CliErrorFormatter() {}

    /**
     * Returns a structured error line.
     *
     * @param code       stable error identifier
     * @param downloadId download ID (optional; omitted when null/blank)
     * @param source     source URI (optional; sanitized to strip credentials)
     * @param message    human-readable description; quoted and escaped
     */
    static String formatError(CliErrorCode code, String downloadId, URI source, String message) {
        final StringBuilder sb = new StringBuilder("ERROR code=").append(code.name());
        if (downloadId != null && !downloadId.isBlank()) {
            sb.append(" downloadId=").append(downloadId);
        }
        if (source != null) {
            sb.append(" source=").append(sanitizeUri(source));
        }
        sb.append(" message=").append(quoteValue(message));
        return sb.toString();
    }

    /**
     * Returns a structured batch summary line.
     *
     * @param total     total inputs submitted (including rejected)
     * @param completed downloads that succeeded
     * @param failed    downloads that failed during execution
     * @param cancelled downloads that were cancelled
     * @param rejected  downloads rejected before submission
     */
    static String formatSummary(int total, int completed, int failed, int cancelled, int rejected) {
        return "SUMMARY total=" + total +
               " completed=" + completed +
               " failed=" + failed +
               " cancelled=" + cancelled +
               " rejected=" + rejected;
    }

    /**
     * Strips the {@code userinfo} component from a URI before including it in output.
     * Returns the string form of the original URI unchanged if it contains no {@code userinfo}.
     */
    static String sanitizeUri(URI uri) {
        if (uri.getUserInfo() == null) {
            return uri.toString();
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                           uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            return uri.getScheme() + "://"
                   + uri.getHost()
                   + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                   + (uri.getPath() != null ? uri.getPath() : "");
        }
    }

    /**
     * Wraps {@code value} in double quotes with internal characters escaped:
     * {@code \} → {@code \\}, {@code "} → {@code \"}, CR → {@code \r},
     * LF → {@code \n}, TAB → {@code \t}.
     */
    static String quoteValue(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }
}
