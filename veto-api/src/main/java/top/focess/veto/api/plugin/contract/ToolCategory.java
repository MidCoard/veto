package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Tool grouping metadata; category membership grants no execution authority. */
public record ToolCategory(@NonNull String label, @NonNull String description) {
    public ToolCategory {
        if (label.isBlank() || label.length() > 128 || description.length() > 4096)
            throw new IllegalArgumentException("Invalid category metadata");
    }
}
