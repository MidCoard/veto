package top.focess.veto.api.http;

import java.net.URI;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record HttpDocument(
        URI uri, int status, String contentType, String content, boolean truncated, int maxChars) {}
