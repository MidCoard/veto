package top.focess.veto.builtin.questions;

import java.util.*;
import org.jspecify.annotations.NonNull;

/** Settled outcome of a question batch: answers keyed by question id, or cancellation. */
public record AnswerBatch(
        @NonNull Map<@NonNull String, @NonNull String> answers, boolean cancelled) {}
