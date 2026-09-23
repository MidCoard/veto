package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contract.Tool;
import top.focess.veto.plugin.contract.ToolContribution;
import top.focess.veto.util.Nullness;

/** Effect-to-capability/danger mapping for plugin-contributed tool definitions. */
class PluginScriptToolDefinitionTest {
    private record Empty() {}

    private static @NonNull RemoteToolDefinition scriptDefinition(Tool.@NonNull Effect effect) {
        var schema = new JsonValue.ObjectValue(Map.of("type", new JsonValue.StringValue("object")));
        return ToolSchemaCompiler.compilePluginScript(
                new ToolContribution(
                        "Fixture tool",
                        schema,
                        schema,
                        effect,
                        Set.of(),
                        (arguments, cancellation) -> JsonValue.NullValue.INSTANCE),
                "plugin_fixture__tool",
                JsonNodeFactory.instance.objectNode(),
                "binding",
                "fixture",
                "1.0.0");
    }

    @Test
    void privilegedEffectMapsToPrivilegedCapabilityWithApprovalDanger() {
        var definition = scriptDefinition(Tool.Effect.PRIVILEGED);
        assertEquals(ToolCapability.PRIVILEGED, definition.capability());
        assertEquals(Danger.DANGEROUS, definition.defaultDanger());
        var provenance = Nullness.requireNonNull(definition.provenance());
        assertEquals("fixture", provenance.pluginId());
        assertEquals("binding", provenance.bindingId());
    }

    @Test
    void otherEffectsStayRemoteUnknownWithElevatedDanger() {
        for (var effect :
                new Tool.Effect[] {Tool.Effect.COMPUTATION, Tool.Effect.EXTERNAL_UNKNOWN}) {
            var definition = scriptDefinition(effect);
            assertEquals(ToolCapability.REMOTE_UNKNOWN, definition.capability());
            assertEquals(Danger.ELEVATED, definition.defaultDanger());
        }
    }

    @Test
    @SuppressWarnings("type.arguments.not.inferred")
    void privilegedCapabilityIsRejectedForNativeAndAgentTools() {
        var nativeDefinition =
                new NativeToolDefinition(
                        "native_fixture",
                        "Native fixture",
                        ToolCapability.PRIVILEGED,
                        Danger.DANGEROUS,
                        false,
                        Object.class,
                        ToolDocs.nonNullClass(Void.class),
                        Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContractValidator.validate(nativeDefinition));
        var agentDefinition =
                AgentToolDefinition.from(
                        "agent_fixture",
                        ToolDocs.nonNullClass(PluginScriptToolDefinitionTest.class),
                        ToolDocs.nonNullClass(Empty.class),
                        ToolCapability.PRIVILEGED);
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContractValidator.validate(agentDefinition));
    }
}
