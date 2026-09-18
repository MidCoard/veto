package top.focess.veto.fixture;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.*;
import top.focess.veto.extension.contract.*;
import top.focess.veto.plugin.api.*;

/** Harmless executable fixture. No Spring, host internals, network or filesystem access. */
public final class FixturePlugin implements VetoPlugin {
    private enum State {
        NEW,
        INITIALIZED,
        ACTIVE,
        CLOSED
    }

    private State state = State.NEW;
    private boolean failStart;

    public FixturePlugin() {}

    @Override
    public synchronized @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws ExtensionFailure {
        if (state != State.NEW) throw new ExtensionFailure(ExtensionFailure.Code.NOT_READY);
        if (!Set.of("failStart").containsAll(configuration.values().keySet()))
            throw new ExtensionFailure(ExtensionFailure.Code.INVALID_CONFIGURATION);
        JsonValue option = configuration.values().get("failStart");
        if (option != null && !(option instanceof JsonValue.BooleanValue))
            throw new ExtensionFailure(ExtensionFailure.Code.INVALID_CONFIGURATION);
        failStart = option instanceof JsonValue.BooleanValue flag && flag.value();
        state = State.INITIALIZED;
        var input =
                new JsonValue.ObjectValue(
                        Map.of(
                                "type", new JsonValue.StringValue("object"),
                                "properties",
                                        new JsonValue.ObjectValue(
                                                Map.of(
                                                        "text",
                                                        new JsonValue.ObjectValue(
                                                                Map.of(
                                                                        "type",
                                                                        new JsonValue.StringValue(
                                                                                "string"))))),
                                "required",
                                        new JsonValue.ArrayValue(
                                                List.of(new JsonValue.StringValue("text"))),
                                "additionalProperties", new JsonValue.BooleanValue(false)));
        var output =
                new JsonValue.ObjectValue(Map.of("type", new JsonValue.StringValue("integer")));
        return new PluginContributions(
                List.of(
                        ExtensionContribution.of(
                                StandardExtensionPoints.TOOLS,
                                "text_length",
                                new ToolContribution(
                                        "Count Unicode code points in text without accessing host services.",
                                        input,
                                        output,
                                        ToolContribution.Effect.COMPUTATION,
                                        Set.of(new ExtensionId("org.veto.fixture:text")),
                                        this::length)),
                        ExtensionContribution.of(
                                StandardExtensionPoints.CATEGORIES,
                                "text",
                                new ToolCategory("Text", "Local text operations")),
                        ExtensionContribution.of(
                                StandardExtensionPoints.PROMPTS,
                                "usage",
                                new PromptContribution("prompts/fixture.md")),
                        ExtensionContribution.of(
                                StandardExtensionPoints.OBSERVATION, "trim", this::trim)));
    }

    @Override
    public synchronized void start() throws ExtensionFailure {
        if (state != State.INITIALIZED) throw new ExtensionFailure(ExtensionFailure.Code.NOT_READY);
        if (failStart) throw new ExtensionFailure(ExtensionFailure.Code.INTERNAL_FAILURE);
        state = State.ACTIVE;
    }

    private synchronized @NonNull JsonValue length(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation invocation)
            throws ExtensionFailure {
        if (state != State.ACTIVE) throw new ExtensionFailure(ExtensionFailure.Code.NOT_READY);
        invocation.checkCancelled();
        if (arguments.values().size() != 1
                || !(arguments.values().get("text") instanceof JsonValue.StringValue text))
            throw new ExtensionFailure(ExtensionFailure.Code.INVALID_ARGUMENTS);
        return new JsonValue.NumberValue(
                java.math.BigDecimal.valueOf(
                        text.value().codePointCount(0, text.value().length())));
    }

    private synchronized @NonNull String trim(
            @NonNull String observation, @NonNull Cancellation cancellation)
            throws ExtensionFailure {
        if (state != State.ACTIVE) throw new ExtensionFailure(ExtensionFailure.Code.NOT_READY);
        cancellation.checkCancelled();
        return observation.strip();
    }

    @Override
    public synchronized void close() {
        state = State.CLOSED;
    }
}
