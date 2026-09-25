package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * Tool grouping metadata; category membership grants no execution authority.
 *
 * @param label short human-readable category label
 * @param description category purpose shown to users and models
 */
public record ToolCategory(@NonNull String label, @NonNull String description) {
    /** Validates display metadata bounds. */
    public ToolCategory {
        if (label.isBlank() || label.length() > 128 || description.length() > 4096)
            throw new IllegalArgumentException("Invalid category metadata");
    }
}
