package jsanca.katabasis.cli;

/**
 * Canonical CLI exit codes.
 *
 * <table>
 *   <caption>Exit code contract</caption>
 *   <tr><th>Code</th><th>Meaning</th></tr>
 *   <tr><td>0</td><td>All requested downloads completed successfully.</td></tr>
 *   <tr><td>1</td><td>Download execution failed; includes all-failed batch.</td></tr>
 *   <tr><td>2</td><td>Invalid CLI arguments, options, or configuration.</td></tr>
 *   <tr><td>3</td><td>Partial batch: at least one succeeded and at least one failed.</td></tr>
 * </table>
 */
final class CliExitCode {

    private CliExitCode() {}

    /** All requested downloads completed successfully. */
    static final int SUCCESS = 0;

    /** Download execution failed; includes all-failed batch. */
    static final int EXECUTION_FAILURE = 1;

    /** Invalid CLI arguments, options, or configuration (picocli USAGE). */
    static final int USAGE_ERROR = 2;

    /** Partial batch failure: at least one download succeeded and at least one failed. */
    static final int PARTIAL_BATCH_FAILURE = 3;
}
