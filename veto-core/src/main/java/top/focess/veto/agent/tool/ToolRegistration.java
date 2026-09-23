package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Compiles all Java contributions through one registration path. */
final class ToolRegistration {
    private ToolRegistration() {}

    static RegisteredTool.@NonNull Local local(
            @NonNull CapabilityTool<?> tool, @NonNull String name, ManagedPlugin runtime) {
        Provenance provenance =
                runtime == null
                        ? null
                        : new Provenance(
                                runtime.identity().id(),
                                runtime.bindingId(),
                                runtime.identity().version());
        LocalToolDefinition definition;
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
                            provenance);
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
                            provenance);
        }
        return new RegisteredTool.Local(definition, tool, runtime);
    }
}
