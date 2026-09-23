package top.focess.veto.fixture;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.PromptContribution;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.api.plugin.contract.ToolCategory;
import top.focess.veto.api.plugin.contract.ToolContribution;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionId;

/** Harmless executable fixture. No Spring, host internals, network or filesystem access. */
public final class FixturePlugin extends AbstractVetoPlugin {
    private boolean failStart;

    public FixturePlugin() {}

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.fixture", "0.1.0");
    }

    @Override
    public @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure {
        if (!Set.of("failStart").containsAll(configuration.values().keySet()))
            throw new PluginFailure(PluginFailure.Code.INVALID_CONFIGURATION);
        JsonValue option = configuration.values().get("failStart");
        if (option != null && !(option instanceof JsonValue.BooleanValue))
            throw new PluginFailure(PluginFailure.Code.INVALID_CONFIGURATION);
        failStart = option instanceof JsonValue.BooleanValue(boolean value) && value;
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
                        Contribution.of(
                                StandardContributionPoints.TOOLS,
                                "text_length",
                                new ToolContribution(
                                        "Count Unicode code points in text without accessing host services.",
                                        input,
                                        output,
                                        Tool.Effect.COMPUTATION,
                                        Set.of(new ContributionId("top.focess.fixture:text")),
                                        this::length)),
                        Contribution.of(
                                StandardContributionPoints.CATEGORIES,
                                "text",
                                new ToolCategory("Text", "Local text operations")),
                        Contribution.of(
                                StandardContributionPoints.PROMPTS,
                                "usage",
                                new PromptContribution("prompts/fixture.md")),
                        Contribution.of(
                                StandardContributionPoints.OBSERVATION, "trim", this::trim)));
    }

    private volatile boolean active;

    @Override
    public void onStart() throws PluginFailure {
        if (failStart) throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        active = true;
    }

    private synchronized @NonNull JsonValue length(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation invocation)
            throws PluginFailure {
        if (!active) throw new PluginFailure(PluginFailure.Code.NOT_READY);
        invocation.checkCancelled();
        if (arguments.values().size() != 1
                || !(arguments.values().get("text") instanceof JsonValue.StringValue text))
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        return new JsonValue.NumberValue(
                java.math.BigDecimal.valueOf(
                        text.value().codePointCount(0, text.value().length())));
    }

    private synchronized @NonNull String trim(
            @NonNull String observation, @NonNull Cancellation cancellation) throws PluginFailure {
        if (!active) throw new PluginFailure(PluginFailure.Code.NOT_READY);
        cancellation.checkCancelled();
        return observation.strip();
    }

    @Override
    public void onClose() {
        active = false;
    }
}
