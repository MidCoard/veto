package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.ResponseCapability;
import top.focess.veto.api.agent.tool.ResponseTool;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** Test-only operation used to exercise call budgets without registering a production no-op. */
@ToolDoc(
        description = "Return a fixture result.",
        behavior = "Returns a fixed test observation.",
        whenToUse = "Exercise test call budgets.",
        whenNotToUse = "Never use outside tests.",
        resultContract = "Plain text fixture result.",
        errorsAndEdgeCases = "Accepts no arguments.",
        security = "Test-only operation with no host access.",
        resultFormats = {ToolResultFormat.PLAINTEXT},
        examples = {"{}"},
        returnExamples = {"fixture result"})
public final class FixtureLoopTool implements ResponseTool<FixtureLoopTool.Args> {
    private final @NonNull ResponseCapability capability;

    public FixtureLoopTool(@NonNull ResponseCapability capability) {
        this.capability = capability;
    }

    @Override
    public @NonNull String getName() {
        return "fixture_loop";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull ResponseCapability responseCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull ResponseCapability access) {
        return "fixture result";
    }

    public record Args() {}
}
