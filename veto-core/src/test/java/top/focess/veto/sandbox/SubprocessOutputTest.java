package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sniffing decode: UTF-8 writers (node/vite) and platform-codepage writers (console
 * CLIs) both come back readable, and a kill-truncated UTF-8 tail degrades to one replacement char
 * instead of mojibake-ing the whole buffer.
 */
class SubprocessOutputTest {

    private static final char REPLACEMENT = (char) 0xFFFD;

    @Test
    void utf8BannerDecodesCorrectly() {
        // The exact case that motivated the sniff: vite writes UTF-8 on a GBK host.
        String banner = "  ➤  Local:   http://localhost:5188/  — 中文也可以";
        byte[] bytes = banner.getBytes(StandardCharsets.UTF_8);
        assertEquals(banner, SubprocessOutput.decode(bytes));
    }

    @Test
    void platformCodepageTextFallsBack() {
        // Encoded with the platform charset (GBK on a zh Windows host): strict UTF-8 fails on
        // these bytes, so decoding must round-trip through the platform fallback.
        String consoleText = "命令输出：共 3 个文件";
        byte[] bytes = consoleText.getBytes(SubprocessOutput.platformCharset());
        assertEquals(consoleText, SubprocessOutput.decode(bytes));
    }

    @Test
    void truncatedUtf8TailYieldsOneReplacementChar() {
        byte[] full = "abc中".getBytes(StandardCharsets.UTF_8);
        byte[] cut = new byte[full.length - 1]; // drops the last byte of a 3-byte char
        System.arraycopy(full, 0, cut, 0, cut.length);
        assertEquals("abc" + REPLACEMENT, SubprocessOutput.decode(cut));
    }

    @Test
    void asciiDecodesAsUtf8() {
        assertEquals("plain ascii 123", SubprocessOutput.decode("plain ascii 123".getBytes()));
    }

    @Test
    void emptyBufferDecodesToEmpty() {
        assertEquals("", SubprocessOutput.decode(new byte[0]));
    }
}
