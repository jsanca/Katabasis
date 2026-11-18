package jsanca.katabasis.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CliDurationConverter}.
 */
class CliDurationConverterTest {

    private final CliDurationConverter converter = new CliDurationConverter();

    // ── valid formats ─────────────────────────────────────────────────────────

    @Test
    void millisecondsAreConverted() throws Exception {
        assertEquals(Duration.ofMillis(500), converter.convert("500ms"));
    }

    @Test
    void secondsAreConverted() throws Exception {
        assertEquals(Duration.ofSeconds(30), converter.convert("30s"));
    }

    @Test
    void minutesAreConverted() throws Exception {
        assertEquals(Duration.ofMinutes(2), converter.convert("2m"));
    }

    @Test
    void hoursAreConverted() throws Exception {
        assertEquals(Duration.ofHours(1), converter.convert("1h"));
    }

    @Test
    void iso8601IsConverted() throws Exception {
        assertEquals(Duration.ofSeconds(30), converter.convert("PT30S"));
    }

    @Test
    void iso8601MinutesAreConverted() throws Exception {
        assertEquals(Duration.ofMinutes(5), converter.convert("PT5M"));
    }

    @Test
    void zeroIsAcceptedSyntactically() throws Exception {
        assertEquals(Duration.ZERO, converter.convert("0s"));
    }

    @Test
    void leadingAndTrailingWhitespaceIsStripped() throws Exception {
        assertEquals(Duration.ofSeconds(10), converter.convert("  10s  "));
    }

    // ── invalid inputs ────────────────────────────────────────────────────────

    @Test
    void emptyStringThrowsTypeConversionException() {
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert(""));
    }

    @Test
    void blankStringThrowsTypeConversionException() {
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert("   "));
    }

    @Test
    void nullThrowsTypeConversionException() {
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert(null));
    }

    @Test
    void unknownSuffixThrowsTypeConversionException() {
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert("30x"));
    }

    @Test
    void nonNumericPrefixWithKnownSuffixThrowsTypeConversionException() {
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert("abcms"));
    }

    @Test
    void malformedIso8601ThrowsTypeConversionException() {
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert("P30"));
    }
}
