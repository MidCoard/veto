package top.focess.veto.fixture;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.*;

/** Compile-time proof that native authoring needs no core classes. */
@ToolSecurity(capability = ToolCapability.NETWORK_EGRESS, defaultDanger = Danger.ELEVATED)
public final class ApiOnlyTool implements NativeTool<ApiOnlyTool.Args> {
    public record Args(@SecurityHint(ParamCategory.URL) @NonNull String url) {}

    @Override
    public @NonNull String getName() {
        return "fixture_url";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        return args.url();
    }
}
