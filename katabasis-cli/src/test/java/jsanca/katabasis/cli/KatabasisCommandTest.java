package jsanca.katabasis.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CLI acceptance tests: TC-CLI-001 through TC-CLI-005.
 *
 * <p>All tests use {@link CommandLine#execute(String...)} directly to obtain the
 * integer exit code without calling {@link System#exit}. This keeps tests
 * in-process and verifiable without subprocess overhead.
 */
class KatabasisCommandTest {

    private static CommandLine cli() {
        return new CommandLine(new KatabasisCommand());
    }

    /** TC-CLI-001: no-args invocation exits 0 */
    @Test
    void noArgsShouldExitZero() {
        assertEquals(0, cli().execute());
    }

    /** TC-CLI-002: --help exits 0 */
    @Test
    void helpFlagShouldExitZero() {
        assertEquals(0, cli().execute("--help"));
    }

    /** TC-CLI-003: --version exits 0 */
    @Test
    void versionFlagShouldExitZero() {
        assertEquals(0, cli().execute("--version"));
    }

    /** TC-CLI-004: unknown option exits 2 (picocli USAGE) */
    @Test
    void unknownOptionShouldExitTwo() {
        assertEquals(2, cli().execute("--bogus-option"));
    }

    /** TC-CLI-005: unknown subcommand exits 2 (picocli USAGE) */
    @Test
    void unknownSubcommandShouldExitTwo() {
        assertEquals(2, cli().execute("foobar"));
    }
}
