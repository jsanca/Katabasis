package jsanca.katabasis.cli;

import picocli.CommandLine;

/**
 * JPMS-compatible entry point for the Katabasis CLI.
 */
public class KatabasisLauncher {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KatabasisCommand()).execute(args);
        System.exit(exitCode);
    }
}
