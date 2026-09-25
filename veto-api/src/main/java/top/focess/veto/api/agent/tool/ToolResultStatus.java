package top.focess.veto.api.agent.tool;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/** Provider-independent execution status retained in the durable tool-result record. */
public enum ToolResultStatus {
    /** Tool execution completed successfully. */
    SUCCESS("success"),
    /** Tool execution ran but failed. */
    FAILURE("failure"),
    /** Policy or user authorization refused execution. */
    REFUSED("refused"),
    /** The owning request was cancelled. */
    CANCELLED("cancelled"),
    /** Execution ended before producing a normal result. */
    INTERRUPTED("interrupted");

    private final @NonNull String id;

    ToolResultStatus(@NonNull String id) {
        this.id = id;
    }

    /**
     * Provides the stable wire identifier.
     *
     * @return stable serialized status identifier
     */
    public @NonNull String id() {
        return id;
    }

    /**
     * Parses a serialized status with a legacy success/failure fallback.
     *
     * @param value serialized status, possibly {@code null}
     * @param fallbackSuccess whether unknown values should be treated as success
     * @return parsed status or the requested fallback
     */
    public static @NonNull ToolResultStatus from(Object value, boolean fallbackSuccess) {
        if (value == null) return fallbackSuccess ? SUCCESS : FAILURE;
        return switch (value.toString().toLowerCase(Locale.ROOT)) {
            case "success" -> SUCCESS;
            case "failure" -> FAILURE;
            case "refused" -> REFUSED;
            case "cancelled" -> CANCELLED;
            case "interrupted" -> INTERRUPTED;
            default -> fallbackSuccess ? SUCCESS : FAILURE;
        };
    }
}
