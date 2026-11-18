package jsanca.katabasis.cli;

/** Stable symbolic error identifiers for structured CLI error output. */
enum CliErrorCode {

    /** A download failed during execution (e.g. HTTP error, I/O error, strategy failure). */
    DOWNLOAD_FAILED,

    /** A download was cancelled explicitly or by graceful shutdown. */
    DOWNLOAD_CANCELLED,

    /** A download submission was rejected before it could start. */
    DOWNLOAD_REJECTED,

    /** An unexpected internal error occurred (timeout, interruption). */
    INTERNAL_ERROR
}
