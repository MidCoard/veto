package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/** Provider-independent execution status retained in the durable tool-result record. */
public enum ToolResultStatus {
    SUCCESS("success"),
    FAILURE("failure"),
    REFUSED("refused"),
    CANCELLED("cancelled"),
    INTERRUPTED("interrupted");

    private final @NonNull String id;

    ToolResultStatus(@NonNull String id) {
        this.id = id;
    }

    public @NonNull String id() {
        return id;
    }

    public static @NonNull ToolResultStatus from(Object value, boolean fallbackSuccess) {
        if (value == null) return fallbackSuccess ? SUCCESS : FAILURE;
        return switch (value.toString().toLowerCase(java.util.Locale.ROOT)) {
            case "success" -> SUCCESS;
            case "failure" -> FAILURE;
            case "refused" -> REFUSED;
            case "cancelled" -> CANCELLED;
            case "interrupted" -> INTERRUPTED;
            default -> fallbackSuccess ? SUCCESS : FAILURE;
        };
    }
}
