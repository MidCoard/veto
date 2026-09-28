package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Tool grouping aspect; category membership grants no execution authority. */
public abstract class ToolCategory {
    private final @NonNull String label;
    private final @NonNull String description;

    /**
     * Constructs validated category display metadata.
     *
     * @param label short category label
     * @param description category purpose shown to users and models
     * @throws IllegalArgumentException when metadata exceeds its bounds
     */
    protected ToolCategory(@NonNull String label, @NonNull String description) {
        if (label.isBlank() || label.length() > 128 || description.length() > 4096)
            throw new IllegalArgumentException("Invalid category metadata");
        this.label = label;
        this.description = description;
    }

    /**
     * Returns short category label.
     *
     * @return short category label
     */
    public final @NonNull String label() {
        return label;
    }

    /**
     * Returns category purpose shown to users and models.
     *
     * @return category purpose shown to users and models
     */
    public final @NonNull String description() {
        return description;
    }
}
