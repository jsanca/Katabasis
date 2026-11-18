package jsanca.katabasis.cli;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a UTF-8 text file containing one absolute URI per line.
 *
 * <p>Blank lines and lines beginning with {@code #} are silently skipped.
 * All remaining lines are validated as absolute URIs before any entries are
 * returned. If any line fails validation, a {@link ParseException} is thrown
 * that accumulates every error.
 */
class UrlListParser {

    /**
     * A successfully parsed URL entry with its original line number for diagnostics.
     */
    record ParsedEntry(int lineNumber, URI uri) {}

    /**
     * Thrown when one or more lines in the URL list file are not valid absolute URIs.
     */
    static final class ParseException extends Exception {

        private final List<String> errors;

        ParseException(List<String> errors) {
            super("URL list contains " + errors.size() + " error(s)");
            this.errors = List.copyOf(errors);
        }

        /**
         * Returns one diagnostic message per invalid line.
         */
        List<String> errors() {
            return errors;
        }
    }

    /**
     * Parses {@code file} and returns validated entries in file order.
     *
     * @param file path to the UTF-8 URL list file
     * @return validated entries, possibly empty
     * @throws IOException        if the file cannot be read
     * @throws ParseException     if any non-blank, non-comment line is not a valid absolute URI
     */
    static List<ParsedEntry> parse(Path file) throws IOException, ParseException {
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        final List<ParsedEntry> entries = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                final URI uri = new URI(line);
                if (!uri.isAbsolute()) {
                    errors.add("Line " + (i + 1) + ": not an absolute URI: " + line);
                } else {
                    entries.add(new ParsedEntry(i + 1, uri));
                }
            } catch (URISyntaxException e) {
                errors.add("Line " + (i + 1) + ": invalid URI: " + line);
            }
        }

        if (!errors.isEmpty()) {
            throw new ParseException(errors);
        }

        return entries;
    }

    private UrlListParser() {}
}
