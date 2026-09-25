package top.focess.veto.builtin.questions;

import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public record Question(
        @StringConstraint(minLength = 1, maxLength = 12)
                @NonNull
                @Doc("Short UI heading, 1-12 Unicode characters.")
                String header,
        @StringConstraint(minLength = 1, pattern = "^[a-z][a-z0-9_]*$")
                @NonNull
                @Doc("Unique snake_case key used in the returned answers object.")
                String id,
        @StringConstraint(minLength = 1, maxLength = 300)
                @NonNull
                @Doc("One-sentence prompt, 1-300 Unicode characters.")
                String question,
        @ArraySize(min = 2, max = 5)
                @NonNull
                @Doc(
                        "Two to five mutually exclusive choices. Put the recommended choice first; the application adds the recommendation marker. Do not add it to labels.")
                List<@NonNull Option> options) {}
