package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A single configurable field of a {@link ModelTierBindingEntity}. Each field is set independently
 * via {@code /modeltier set <profile> <tier> <field> <value>} (the per-field "optional" update
 * model), so a user builds a binding one field at a time - provider, base URL, model,
 * credential-key, temperature, max output tokens.
 *
 * <p>The string names ({@link #field}) are the wire forms used on the command line and persisted as
 * the {@code set <field>} token; they are stable identifiers (never the enum constant names, which
 * could be renamed).
 */
public enum ModelTierField {
    PROVIDER("provider"),
    BASE_URL("baseUrl"),
    MODEL("model"),
    CREDENTIAL_KEY("credKey"),
    TEMPERATURE("temp"),
    MAX_OUTPUT_TOKENS("max");

    private final @NonNull String field;

    ModelTierField(@NonNull String field) {
        this.field = field;
    }

    /**
     * The stable wire name used on the command line ({@code /modeltier set <profile> <tier> <field>
     * ...}).
     */
    public @NonNull String field() {
        return field;
    }

    /**
     * Parse a wire name to its field, case-insensitively.
     *
     * @return the field, or null if {@code s} does not name a field
     */
    public static @Nullable ModelTierField fromField(@NonNull String s) {
        for (ModelTierField f : values()) {
            if (f.field.equalsIgnoreCase(s)) {
                return f;
            }
        }
        return null;
    }
}
