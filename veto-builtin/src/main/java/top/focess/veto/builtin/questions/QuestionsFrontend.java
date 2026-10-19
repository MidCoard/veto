package top.focess.veto.builtin.questions;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.builtin.BundledFrontend;

/** Authenticated frontend actions share the exact tool invocation scope. */
public final class QuestionsFrontend extends BundledFrontend {
    private final @NonNull QuestionRuntime runtime;

    /** Creates a frontend backed by the given question runtime. */
    public QuestionsFrontend(@NonNull QuestionRuntime runtime) {
        super("/frontend/questions.js");
        this.runtime = runtime;
    }

    /**
     * Handles {@code list}, {@code answer} and {@code cancel} for the authenticated agent scope.
     * Each response contains the current agent's pending questions and revision, matching the live
     * {@code questions_changed} action. The panel uses {@code list} for initial load and recovery,
     * and applies mutation responses directly.
     */
    public @NonNull JsonValue handle(
            Scope.@NonNull AgentScope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue args)
            throws PluginFailure {
        if (action.equals("list")) return runtime.snapshot(scope);
        if (!(args.values().get("callId") instanceof JsonValue.StringValue callId)) throw invalid();
        boolean accepted;
        if (action.equals("cancel")) {
            accepted = runtime.cancel(scope, callId.value());
        } else if (action.equals("answer")) {
            if (!(args.values().get("answers") instanceof JsonValue.ObjectValue values))
                throw invalid();
            Map<@NonNull String, @NonNull String> answers = new LinkedHashMap<>();
            for (var entry : values.values().entrySet()) {
                if (!(entry.getValue() instanceof JsonValue.StringValue answer)) throw invalid();
                answers.put(entry.getKey(), answer.value());
            }
            accepted = runtime.answer(scope, callId.value(), answers);
        } else throw invalid();
        if (!accepted) throw invalid();
        return runtime.snapshot(scope);
    }

    private static @NonNull PluginFailure invalid() {
        return new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
    }
}
