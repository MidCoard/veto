package top.focess.veto.api.http;

import java.net.URI;
import org.jspecify.annotations.NullMarked;

/**
 * Bounded HTTP response returned from a screened destination.
 *
 * @param uri resolved response URI
 * @param status HTTP status code
 * @param contentType response media type
 * @param content bounded text content
 * @param truncated whether content exceeded the host limit
 * @param maxChars host text-character limit
 */
@NullMarked
public record HttpDocument(
        URI uri, int status, String contentType, String content, boolean truncated, int maxChars) {}
