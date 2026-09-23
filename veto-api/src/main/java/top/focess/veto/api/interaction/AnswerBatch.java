package top.focess.veto.api.interaction;

import java.time.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public record AnswerBatch(
        @NonNull Map<@NonNull String, @NonNull String> answers, boolean cancelled) {}
