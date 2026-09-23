package top.focess.veto.api.web;

import java.util.List;
import org.jspecify.annotations.NonNull;

public record Result(
        @NonNull String outcome,
        @NonNull String answer,
        @NonNull List<@NonNull Evidence> evidence,
        @NonNull List<@NonNull String> limitations,
        @NonNull Execution execution) {}
