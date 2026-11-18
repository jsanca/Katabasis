package jsanca.katabasis.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Root command for the Katabasis CLI.
 *
 * <p>Invoking {@code katabasis} with no subcommand prints help and exits 0.
 * Subcommands are added in later tasks (K-0.2-T04 through K-0.2-T07).
 */
@Command(
        name = "katabasis",
        mixinStandardHelpOptions = true,
        version = "katabasis 0.2",
        description = "High-performance, multi-protocol download manager.",
        subcommands = {DownloadCommand.class, DownloadListCommand.class}
)
public class KatabasisCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }
}
