package top.focess.veto.builtin.questions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Authenticated frontend actions share the exact tool invocation scope. */
public final class QuestionsFrontend {
    private final @NonNull QuestionRuntime runtime;
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    /** Creates a frontend backed by the given question runtime. */
    public QuestionsFrontend(@NonNull QuestionRuntime runtime) {
        this.runtime = runtime;
    }

    /** Serves the bundled interactions script and routes its actions to {@link #handle}. */
    public @NonNull FrontendContribution contribution() {
        try (var stream =
                ToolDocs.nonNullClass(QuestionsFrontend.class)
                        .getResourceAsStream("/frontend/questions.js")) {
            if (stream == null) throw new IllegalStateException("Missing questions frontend");
            return new FrontendContribution(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), this::handle);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load questions frontend", failure);
        }
    }

    /** Answers frontend actions ({@code list}, {@code answer}, {@code cancel}). */
    public @NonNull JsonValue handle(
            FrontendContribution.@NonNull Scope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue args)
            throws PluginFailure {
        if (action.equals("list"))
            return JsonValues.from(mapper.valueToTree(Map.of("items", runtime.pendingFor(scope))));
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
        return new JsonValue.BooleanValue(true);
    }

    private static @NonNull PluginFailure invalid() {
        return new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
    }
}
