package jsanca.katabasis.cli;

import picocli.CommandLine;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * picocli type converter for user-friendly duration strings.
 *
 * <p>Accepted formats:
 * <ul>
 *   <li>{@code Nms} — milliseconds (e.g., {@code 500ms})</li>
 *   <li>{@code Ns}  — seconds (e.g., {@code 30s})</li>
 *   <li>{@code Nm}  — minutes (e.g., {@code 2m})</li>
 *   <li>{@code Nh}  — hours (e.g., {@code 1h})</li>
 *   <li>ISO-8601 — (e.g., {@code PT30S}, {@code PT2M})</li>
 * </ul>
 *
 * <p>Zero and negative values are syntactically accepted; the caller is responsible
 * for rejecting them when a positive duration is required.
 */
public class CliDurationConverter implements CommandLine.ITypeConverter<Duration> {

    @Override
    public Duration convert(String value) throws Exception {
        if (value == null || value.isBlank()) {
            throw new CommandLine.TypeConversionException("Duration value must not be blank");
        }
        final String v = value.trim();
        try {
            if (v.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
            }
            if (v.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (v.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (v.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            return Duration.parse(v);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new CommandLine.TypeConversionException(
                    "Invalid duration '" + value + "'. Use formats like: 30s, 2m, 500ms, 1h, PT30S");
        }
    }
}
