package top.focess.veto.api.agent.tool;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/** The content encodings a successful tool result may use. Status is carried separately. */
public enum ToolResultFormat {
    /** One complete JSON value. */
    JSON("json", "a single JSON value"),
    /** Ordinary human-readable text. */
    PLAINTEXT("plaintext", "ordinary text"),
    /** Content for which the tool did not declare an encoding. */
    UNKNOWN("unknown", "content whose encoding is not declared by the tool");

    private final @NonNull String id;
    private final @NonNull String description;

    ToolResultFormat(@NonNull String id, @NonNull String description) {
        this.id = id;
        this.description = description;
    }

    /**
     * Provides the stable wire identifier.
     *
     * @return stable serialized format identifier
     */
    public @NonNull String id() {
        return id;
    }

    /**
     * Explains the content representation to documentation consumers.
     *
     * @return human-readable description of the encoding
     */
    public @NonNull String description() {
        return description;
    }

    /**
     * Parses a serialized identifier without failing on legacy values.
     *
     * @param value serialized identifier, possibly {@code null}
     * @return matching format, or {@link #UNKNOWN}
     */
    public static @NonNull ToolResultFormat fromId(Object value) {
        if (value == null) return UNKNOWN;
        return switch (value.toString().toLowerCase(Locale.ROOT)) {
            case "json" -> JSON;
            case "plaintext" -> PLAINTEXT;
            default -> UNKNOWN;
        };
    }
}
