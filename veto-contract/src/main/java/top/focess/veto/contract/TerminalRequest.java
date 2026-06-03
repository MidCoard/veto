package top.focess.veto.contract;

import java.util.Collections;
import java.util.Map;

/**
 * What the terminal sends to the backend. {@code raw} is the unparsed user input. {@code
 * sessionToken} is null when not logged in.
 */
public record TerminalRequest(String raw, String sessionToken) {

    public TerminalRequest(String raw) {
        this(raw, null);
    }

    public static Map<String, Object> emptyMeta() {
        return Collections.emptyMap();
    }
}
