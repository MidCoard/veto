package top.focess.veto.builtin.questions;

import java.util.*;
import org.jspecify.annotations.NonNull;

public record AnswerBatch(
        @NonNull Map<@NonNull String, @NonNull String> answers, boolean cancelled) {}
