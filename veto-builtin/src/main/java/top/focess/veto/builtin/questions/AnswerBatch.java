package top.focess.veto.builtin.questions;

import java.time.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public record AnswerBatch(
        @NonNull Map<@NonNull String, @NonNull String> answers, boolean cancelled) {}
