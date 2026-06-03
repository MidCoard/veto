package top.focess.veto.contract;

import java.util.Collections;
import java.util.Map;

/**
 * Structured response from backend to terminal. The terminal maps {@link #type} to visual rendering
 * — it never generates ANSI or layout on the backend.
 */
public record TerminalResponse(ResponseType type, String content, Map<String, Object> meta) {

    public TerminalResponse(ResponseType type, String content) {
        this(type, content, Collections.emptyMap());
    }

    public static TerminalResponse error(String message) {
        return new TerminalResponse(ResponseType.ERROR, message);
    }
}
