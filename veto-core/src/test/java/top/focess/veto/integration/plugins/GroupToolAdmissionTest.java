package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.Provenance;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.group.Blackboard;
import top.focess.veto.builtin.group.BlackboardMessage;
import top.focess.veto.builtin.group.ExecutionDag;
import top.focess.veto.builtin.group.Group;
import top.focess.veto.builtin.group.GroupRegistry;
import top.focess.veto.builtin.group.GroupRuntime;
import top.focess.veto.builtin.group.GroupState;
import top.focess.veto.builtin.group.GroupTools;
import top.focess.veto.bus.SessionInvalidations;

class GroupToolAdmissionTest {
    @Test
    void groupSnapshotsAndActionsUseTheirOwnExactPermitWithQualifiedNamesAndAliases()
            throws Exception {
        for (boolean alias : List.of(false, true)) {
            try (var fixture = new PluginAgentHostsTest.Fixture()) {
                var grant =
                        new PluginStorage.Grant<>(
                                "group-account",
                                new Scope.SessionScope(
                                        "immutable-account", fixture.session.getId()));
                when(fixture.storage.scopes(PluginScope.SESSION, null, 200))
                        .thenReturn(new PluginStorage.Page<>(List.of(grant), null));
                when(fixture.scopes.authorizeSession(fixture.storage, grant)).thenReturn("owner");
                var configuration = new PluginHostConfiguration();
                var delegate =
                        (PluginHost)
                                configuration
                                        .runtimeHostServices(
                                                PluginTestSupport.providerOf(null),
                                                        PluginTestSupport.providerOf(null),
                                                PluginTestSupport.providerOf(null),
                                                        PluginTestSupport.providerOf(
                                                                mock(SessionInvalidations.class)))
                                        .services()
                                        .get(PluginHost.class);
                if (delegate == null) throw new AssertionError("Missing host");
                var host =
                        new BoundPluginHost(
                                delegate,
                                fixture.plugin,
                                fixture.storage,
                                fixture.scopes,
                                local ->
                                        alias ? "custom_" + local : "plugin_test_plugin__" + local);
                var prompts = mock(PromptRenderer.class);
                when(prompts.compile(anyString(), anyMap())).thenReturn("Completed group");
                try (var runtime =
                        new GroupRuntime(
                                new PluginContext(
                                        fixture.plugin.identity(),
                                        () -> {},
                                        fixture.plugin::state,
                                        Map.of(
                                                PluginHost.class,
                                                host,
                                                PromptRenderer.class,
                                                prompts),
                                        Map.of()),
                                new JsonValue.ObjectValue(Map.of()))) {
                    GroupRegistry registry =
                            (GroupRegistry) ReflectionTestUtils.getField(runtime, "groups");
                    Blackboard board = (Blackboard) ReflectionTestUtils.getField(runtime, "board");
                    if (registry == null || board == null)
                        throw new AssertionError("Missing group state");
                    var group =
                            Group.create(
                                    fixture.childId,
                                    grant.scope().owner(),
                                    "work",
                                    board,
                                    new ExecutionDag(UUID.randomUUID(), List.of()),
                                    "owner",
                                    null,
                                    ToolResultPresentationMode.BASIC,
                                    UUID.fromString(fixture.session.getId()));
                    registry.put(group);
                    var operations = runtime.operations();
                    for (String local :
                            List.of(
                                    "inspect_group",
                                    "create_task",
                                    "create_node",
                                    "remove_node",
                                    "post_message",
                                    "disband_group")) {
                        String name = alias ? "custom_" + local : "plugin_test_plugin__" + local;
                        var definition =
                                new NativeToolDefinition(
                                        name,
                                        local,
                                        ToolCapability.PLUGIN_LOCAL,
                                        Danger.SAFE,
                                        false,
                                        GroupToolAdmissionTest.class,
                                        GroupToolAdmissionTest.class,
                                        Map.of(),
                                        new Provenance(
                                                "test.plugin",
                                                fixture.plugin.bindingId(),
                                                "1.0.0",
                                                local));
                        var user = UUID.randomUUID();
                        var session = UUID.fromString(fixture.session.getId());
                        var permit =
                                ToolExecutionPermit.capture(
                                                new ToolCall(name, Map.of(), "call"),
                                                definition,
                                                Workspace.single(Path.of("."), PathMode.REAL))
                                        .withCaller(fixture.childId, user, "owner", session);
                        ToolCallContextHolder.set(
                                new ToolCallContext(
                                        fixture.childId,
                                        user,
                                        "owner",
                                        session,
                                        ToolResultPresentationMode.BASIC,
                                        permit,
                                        "request"));
                        ReflectionTestUtils.invokeMethod(
                                ToolCallContextHolder.class, "setCurrentCallId", "call");
                        try {
                            var snapshot = operations.snapshot(local);
                            if (snapshot == null)
                                throw new AssertionError("Missing group snapshot");
                            assertEquals(group.groupId(), snapshot.groupId());
                            assertThrows(
                                    SecurityException.class,
                                    () -> operations.snapshot("wrong_tool"));
                            if (local.equals("post_message")) {
                                new GroupTools.PostMessage(operations)
                                        .execute(
                                                new GroupTools.PostMessage.Args(
                                                        BlackboardMessage.MessageType.STATUS,
                                                        "LEADER",
                                                        "progress"));
                                assertEquals(1, board.size(group.groupId()));
                            } else if (local.equals("disband_group")) {
                                assertEquals(
                                        "",
                                        new GroupTools.DisbandGroup(operations)
                                                .execute(new GroupTools.DisbandGroup.Args()));
                                var disbanded = registry.get(group.groupId());
                                if (disbanded == null)
                                    throw new AssertionError("Missing disbanded group");
                                assertEquals(GroupState.DISBANDED, disbanded.state());
                            }
                        } finally {
                            ToolCallContextHolder.clear();
                        }
                    }
                }
            }
        }
    }
}
