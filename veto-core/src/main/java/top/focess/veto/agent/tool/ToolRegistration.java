package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolPresentation;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Compiles all Java contributions through one registration path. */
final class ToolRegistration {
    private ToolRegistration() {}

    static RegisteredTool.@NonNull Local local(
            @NonNull CapabilityTool<?> tool, @NonNull String name, ManagedPlugin runtime) {
        return local(tool, name, runtime, tool.getName());
    }

    static RegisteredTool.@NonNull Local local(
            @NonNull CapabilityTool<?> tool,
            @NonNull String name,
            ManagedPlugin runtime,
            @NonNull String localId) {
        Provenance provenance =
                runtime == null
                        ? null
                        : new Provenance(
                                runtime.identity().id(),
                                runtime.bindingId(),
                                runtime.identity().version(),
                                localId);
        LocalToolDefinition definition;
        ToolPresentation presentation =
                tool instanceof ToolPresentation callback
                        ? workspace -> {
                            try {
                                return runtime == null
                                        ? ToolCallContextHolder.withoutEffects(
                                                () -> callback.describe(workspace))
                                        : runtime.execute(
                                                () ->
                                                        ToolCallContextHolder.withoutEffects(
                                                                () ->
                                                                        callback.describe(
                                                                                workspace)));
                            } catch (Exception failure) {
                                return new ToolPresentation.State(false, java.util.Map.of());
                            }
                        }
                        : null;
        if (tool instanceof AgentTool<?>) {
            definition =
                    new AgentToolDefinition(
                            name,
                            ToolDocs.descriptionOf(tool.getClass()),
                            tool.getCapability(),
                            Danger.SAFE,
                            tool.getClass(),
                            tool.getArgsClass(),
                            ToolSchemaCompiler.hintsOf(tool.getArgsClass()),
                            provenance,
                            presentation);
        } else {
            ToolSecurity security = ToolSchemaCompiler.securityOf(tool.getClass());
            definition =
                    new NativeToolDefinition(
                            name,
                            ToolDocs.descriptionOf(tool.getClass()),
                            tool.getCapability(),
                            security.defaultDanger(),
                            security.requiresSemanticScreening(),
                            tool.getClass(),
                            tool.getArgsClass(),
                            ToolSchemaCompiler.hintsOf(tool.getArgsClass()),
                            provenance,
                            presentation);
        }
        return new RegisteredTool.Local(definition, tool, runtime);
    }
}
