package top.focess.veto.builtin.web.model;

import java.net.URI;
import org.jspecify.annotations.NonNull;

/** A bounded HTTP response, before tool-specific document parsing and presentation. */
public record FetchedPage(
        @NonNull URI uri,
        int status,
        @NonNull String contentType,
        @NonNull String content,
        boolean truncated,
        int characterLimit) {}
