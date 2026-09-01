package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/** The content encodings a successful tool result may use. Status is carried separately. */
public enum ToolResultFormat {
    JSON("json", "a single JSON value"),
    PLAINTEXT("plaintext", "ordinary text"),
    UNKNOWN("unknown", "content whose encoding is not declared by the tool");

    private final @NonNull String id;
    private final @NonNull String description;

    ToolResultFormat(@NonNull String id, @NonNull String description) {
        this.id = id;
        this.description = description;
    }

    public @NonNull String id() {
        return id;
    }

    public @NonNull String description() {
        return description;
    }

    public static @NonNull ToolResultFormat fromId(Object value) {
        if (value == null) return UNKNOWN;
        return switch (value.toString().toLowerCase(java.util.Locale.ROOT)) {
            case "json" -> JSON;
            case "plaintext" -> PLAINTEXT;
            default -> UNKNOWN;
        };
    }
}
