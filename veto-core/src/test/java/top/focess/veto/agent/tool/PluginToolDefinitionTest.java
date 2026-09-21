package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contract.ToolContribution;

/** Effect-to-capability/danger mapping for plugin-contributed tool descriptors. */
class PluginToolDefinitionTest {
    private record Empty() {}

    private static @NonNull PluginToolDefinition definition(
            ToolContribution.@NonNull Effect effect) {
        var schema = new JsonValue.ObjectValue(Map.of("type", new JsonValue.StringValue("object")));
        return new PluginToolDefinition(
                "plugin_fixture__tool",
                "binding",
                "fixture",
                "1.0.0",
                new ToolContribution(
                        "Fixture tool",
                        schema,
                        schema,
                        effect,
                        Set.of(),
                        (arguments, cancellation) -> JsonValue.NullValue.INSTANCE));
    }

    @Test
    void privilegedEffectMapsToPrivilegedCapabilityWithApprovalDanger() {
        var definition = definition(Tool.Effect.PRIVILEGED);
        assertEquals(ToolCapability.PRIVILEGED, definition.capability());
        assertEquals(Danger.DANGEROUS, definition.defaultDanger());
    }

    @Test
    void otherEffectsStayRemoteUnknownWithElevatedDanger() {
        for (var effect :
                new Tool.Effect[] {
                    Tool.Effect.COMPUTATION, Tool.Effect.EXTERNAL_UNKNOWN
                }) {
            var definition = definition(effect);
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
                        ToolDocs.nonNullClass(PluginToolDefinitionTest.class),
                        ToolDocs.nonNullClass(Empty.class),
                        ToolCapability.PRIVILEGED);
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContractValidator.validate(agentDefinition));
    }
}
