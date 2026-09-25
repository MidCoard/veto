package top.focess.veto.builtin.web.model;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Settled outcome of one web read. */
public record Result(
        @NonNull String outcome,
        @NonNull String answer,
        @NonNull List<@NonNull Evidence> evidence,
        @NonNull List<@NonNull String> limitations,
        @NonNull Execution execution) {}
