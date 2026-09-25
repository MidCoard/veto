package top.focess.veto.builtin.questions;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

/** One selectable choice offered with a {@link Question}. */
public record Option(
        @StringConstraint(minLength = 1, maxLength = 120)
                @NonNull
                @Doc(
                        "Plain choice label, 1-120 Unicode characters. The application marks the first option as recommended; do not write `(Recommended)` yourself. Move explanations into the description; `Other` is reserved.")
                String label,
        @StringConstraint(minLength = 1, maxLength = 200)
                @NonNull
                @Doc(
                        "One short sentence, 1-200 Unicode characters, explaining the choice's impact.")
                String description) {}
