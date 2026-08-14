package top.focess.veto.sandbox;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;

/**
 * The decode strategy for sandboxed-process output. Two writer populations share the pipe: Windows
 * console CLIs (cmd builtins, legacy toolchains) write the system codepage (GBK/CP936 on a zh
 * host), while cross-platform runtimes (node, python, git, most modern JVM CLIs) write UTF-8
 * regardless of locale — vite's {@code ➤} banner arrives as UTF-8 bytes even on a GBK host. A fixed
 * charset garbles one population or the other, so decoding sniffs instead: strict UTF-8 first — its
 * byte structure is restrictive enough that genuine GBK text virtually never passes it — then the
 * platform's native encoding (the old {@code native.encoding} behavior, still UTF-8 on Linux, where
 * nothing changes).
 *
 * <p>A trailing incomplete UTF-8 sequence — a force-killed process cut mid-character — decodes to
 * one replacement char rather than flipping the whole buffer to the platform charset.
 */
final class SubprocessOutput {

    private static final @NonNull Charset PLATFORM = resolvePlatform();

    private SubprocessOutput() {}

    /** Decodes raw subprocess bytes: strict UTF-8 when the buffer qualifies, platform otherwise. */
    static @NonNull String decode(byte @NonNull [] bytes) {
        String strict = tryUtf8(bytes, bytes.length);
        if (strict != null) {
            return strict;
        }
        // Trailing truncation: dropping 1-3 bytes may leave clean UTF-8 whose final character was
        // cut. The lead-byte check distinguishes that from GBK text whose last bytes merely
        // happen to decode after trimming (GBK then keeps the platform fallback).
        for (int trim = 1; trim <= 3 && trim < bytes.length; trim++) {
            int cut = bytes.length - trim;
            String prefix = tryUtf8(bytes, cut);
            if (prefix != null && isUtf8Lead(bytes[cut])) {
                return prefix + '\uFFFD';
            }
        }
        return new String(bytes, PLATFORM);
    }

    /** The platform charset the fallback uses (package-visible for tests). */
    static @NonNull Charset platformCharset() {
        return PLATFORM;
    }

    /** Strict-decodes {@code bytes[0, len)} as UTF-8; {@code null} on any malformed input. */
    private static String tryUtf8(byte @NonNull [] bytes, int len) {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, 0, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** True when {@code b} can open a multi-byte UTF-8 sequence (0xC2-0xF4). */
    private static boolean isUtf8Lead(byte b) {
        int v = b & 0xFF;
        return v >= 0xC2 && v <= 0xF4;
    }

    private static @NonNull Charset resolvePlatform() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (Exception ignored) {
                // Unknown name — fall through to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }
}
