package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AnsiEscapes#strip} against the escape shapes real CLIs emit — the Vite-style SGR
 * banner that motivated the scrub, cursor/erase codes, and OSC titles/hyperlinks.
 */
class AnsiEscapesTest {

    /** The escape byte, built numerically so this source file carries no raw control chars. */
    private static final String ESC = String.valueOf((char) 0x1B);

    @Test
    void stripsViteStyleSgrBanner() {
        String raw =
                " "
                        + ESC
                        + "[32m➤"
                        + ESC
                        + "[39m  "
                        + ESC
                        + "[1mVITE"
                        + ESC
                        + "[22m v5.4.21  "
                        + ESC
                        + "[2mready in"
                        + ESC
                        + "[0m "
                        + ESC
                        + "[1m704"
                        + ESC
                        + "[22m"
                        + ESC
                        + "[2m ms"
                        + ESC
                        + "[0m";
        assertEquals(" ➤  VITE v5.4.21  ready in 704 ms", AnsiEscapes.strip(raw));
    }

    @Test
    void stripsCursorAndEraseCodes() {
        assertEquals("text", AnsiEscapes.strip(ESC + "[2J" + ESC + "[H" + ESC + "[10;5Htext"));
        assertEquals("line", AnsiEscapes.strip(ESC + "[2Kline" + ESC + "[1G"));
    }

    @Test
    void stripsOscTitleAndHyperlink() {
        assertEquals("rest", AnsiEscapes.strip(ESC + "]0;window titlerest"));
        assertEquals(
                "link",
                AnsiEscapes.strip(
                        ESC
                                + "]8;;https://example.com"
                                + ESC
                                + "\\link"
                                + ESC
                                + "]8;;"
                                + ESC
                                + "\\"));
    }

    @Test
    void stripsCharsetDesignatorAndFeEscapes() {
        assertEquals("abc", AnsiEscapes.strip(ESC + "(Babc"));
        // Fe escapes take a final byte in 0x40-0x5F (here: ESC M, reverse index).
        assertEquals("x", AnsiEscapes.strip(ESC + "Mx"));
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        String plain = "Local:   http://localhost:5188/ — plain output, exit code: 0";
        assertEquals(plain, AnsiEscapes.strip(plain));
    }

    @Test
    void emptyStringStaysEmpty() {
        assertEquals("", AnsiEscapes.strip(""));
    }
}
