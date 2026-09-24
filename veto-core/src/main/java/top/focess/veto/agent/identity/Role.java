package top.focess.veto.agent.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;

/** Historical/display label only. It never grants tools or selects model instructions. */
public record Role(@NonNull String name) {
    public static final @NonNull Role STANDALONE = new Role("STANDALONE");
    public static final @NonNull Role LEADER = new Role("LEADER");
    public static final @NonNull Role MATE = new Role("MATE");

    public Role {
        if (name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Invalid agent label");
    }

    @JsonCreator
    public static @NonNull Role valueOf(@NonNull String name) {
        return new Role(name);
    }

    @Override
    public @NonNull String toString() {
        return name;
    }

    @JsonValue
    public @NonNull String name() {
        return name;
    }
}
