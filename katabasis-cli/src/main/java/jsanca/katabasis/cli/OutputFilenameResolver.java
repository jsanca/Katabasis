package jsanca.katabasis.cli;

import java.net.URI;
import java.nio.file.Path;

/**
 * Derives a safe output filename from a URI and resolves it under a given output directory.
 *
 * <p>The filename is taken from the last path segment of the URI after stripping trailing
 * slashes. {@link URI#getPath()} is used so percent-encoded characters are already decoded.
 *
 * <p>The resolved path is normalized and verified not to escape the output directory,
 * which catches segment names such as {@code ..}.
 */
class OutputFilenameResolver {

    /**
     * Resolves the output path for {@code uri} under {@code outputDir}.
     *
     * @param uri       the source URI; its path segment determines the filename
     * @param outputDir the directory under which the file will be written
     * @return the resolved, normalized output path
     * @throws IllegalArgumentException if no safe filename can be derived
     */
    static Path resolve(URI uri, Path outputDir) {
        final String uriPath = uri.getPath();
        if (uriPath == null || uriPath.isBlank()) {
            throw new IllegalArgumentException("Cannot derive filename from URI: " + uri);
        }

        String stripped = uriPath;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.isBlank()) {
            throw new IllegalArgumentException("Cannot derive filename from URI: " + uri);
        }

        final int slash = stripped.lastIndexOf('/');
        final String filename = slash >= 0 ? stripped.substring(slash + 1) : stripped;

        if (filename.isBlank()) {
            throw new IllegalArgumentException("Cannot derive filename from URI: " + uri);
        }
        if (filename.contains("\0") || filename.contains("\\")) {
            throw new IllegalArgumentException(
                    "Invalid character in filename derived from URI: " + uri);
        }

        final Path resolved = outputDir.resolve(filename).normalize();
        if (!resolved.startsWith(outputDir.normalize())) {
            throw new IllegalArgumentException(
                    "Filename derived from URI would escape output directory: " + uri);
        }
        return resolved;
    }

    private OutputFilenameResolver() {}
}
