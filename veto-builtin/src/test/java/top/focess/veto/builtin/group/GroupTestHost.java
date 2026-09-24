package top.focess.veto.builtin.group;

import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** The plugin uses only API host handles, with no core agent or persistence classes. */
final class GroupTestHost implements AutoCloseable {
    final @NonNull PluginStorage storage = mock(ToolDocs.nonNullClass(PluginStorage.class));
    final @NonNull PluginHost host = mock(ToolDocs.nonNullClass(PluginHost.class));
    final AgentHost.@NonNull Session agents = mock(ToolDocs.nonNullClass(AgentHost.Session.class));
    final PluginStorage.@NonNull SessionScope scope =
            new PluginStorage.SessionScope("scope", "user", UUID.randomUUID().toString());
    final @NonNull Map<String, PluginStorage.Entry> rows = new ConcurrentHashMap<>();
    final @NonNull GroupRuntime runtime;
    final AgentConfiguration.@NonNull Context configuration;
    PluginHost.@NonNull Invocation caller;

    GroupTestHost() {
        caller =
                new PluginHost.Invocation(
                        "owner", scope.sessionId(), "leader", "request-one", "test-call");
        var store = mock(ToolDocs.nonNullClass(PluginStorage.Store.class));
        when(storage.session(any())).thenReturn(store);
        when(storage.scopes(any(), any(), anyInt()))
                .thenReturn(new PluginStorage.Page<>(List.of(scope), null));
        when(store.get(anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(required(call.getArgument(0)))));
        when(store.list(anyString(), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            String prefix = required(call.getArgument(0));
                            String cursor = call.getArgument(1);
                            int limit = call.getArgument(2);
                            var found =
                                    rows.values().stream()
                                            .filter(
                                                    row ->
                                                            row.key().startsWith(prefix)
                                                                    && (cursor == null
                                                                            || row.key()
                                                                                            .compareTo(
                                                                                                    cursor)
                                                                                    > 0))
                                            .sorted(Comparator.comparing(PluginStorage.Entry::key))
                                            .toList();
                            var page = found.stream().limit(limit).toList();
                            return new PluginStorage.Page<>(
                                    page, found.size() > limit ? page.getLast().key() : null);
                        });
        when(store.put(anyString(), any(), any()))
                .thenAnswer(
                        call -> {
                            String key = required(call.getArgument(0));
                            String expected = call.getArgument(1);
                            PluginStorage.Document document = required(call.getArgument(2));
                            synchronized (rows) {
                                var old = rows.get(key);
                                if (!Objects.equals(expected, old == null ? null : old.revision()))
                                    throw new PluginStorage.Conflict();
                                var entry =
                                        new PluginStorage.Entry(
                                                key, UUID.randomUUID().toString(), document);
                                rows.put(key, entry);
                                return entry;
                            }
                        });
        doAnswer(
                        call -> {
                            String key = required(call.getArgument(0));
                            String expected = call.getArgument(1);
                            synchronized (rows) {
                                var old = rows.get(key);
                                if (old == null || !old.revision().equals(expected))
                                    throw new PluginStorage.Conflict();
                                rows.remove(key);
                            }
                            return null;
                        })
                .when(store)
                .delete(anyString(), anyString());
        when(host.invocation(anyString())).thenAnswer(call -> caller);
        when(agents.id()).thenReturn(scope.sessionId());
        when(agents.open(anyString(), anyString(), any()))
                .thenAnswer(call -> child(required(call.getArgument(0))));
        var context = mock(ToolDocs.nonNullClass(PluginContext.class));
        when(context.service(ToolDocs.nonNullClass(PluginHost.class)))
                .thenReturn(Optional.of(host));
        when(context.service(ToolDocs.nonNullClass(PluginStorage.class)))
                .thenReturn(Optional.of(storage));
        PromptRenderer prompts = (name, data) -> name + " " + data;
        when(context.service(ToolDocs.nonNullClass(PromptRenderer.class)))
                .thenReturn(Optional.of(prompts));
        runtime = new GroupRuntime(context);
        var tools =
                Set.of(
                        "create_group",
                        "create_mate",
                        "create_task",
                        "inspect_group",
                        "disband_group",
                        "post_message",
                        "remove_mate",
                        "cancel_group_task",
                        "view_file");
        var profile =
                new AgentProfile("Leader", "work", "STANDALONE", tools, "DEFAULT", null, Map.of());
        configuration =
                new AgentConfiguration.Context(
                        "owner",
                        scope,
                        agents,
                        "leader",
                        profile,
                        tools.stream()
                                .map(
                                        name ->
                                                new AgentConfiguration.Tool(
                                                        name,
                                                        ToolCapability.PLUGIN_LOCAL,
                                                        "top.focess.builtin",
                                                        name))
                                .toList(),
                        "original task");
    }

    static AgentHost.@NonNull Child child(@NonNull String id) throws InterruptedException {
        var child = mock(ToolDocs.nonNullClass(AgentHost.Child.class));
        when(child.id()).thenReturn(id);
        when(child.state()).thenReturn(AgentState.IDLE);
        when(child.awaitTermination(any(ToolDocs.nonNullClass(Duration.class)))).thenReturn(true);
        return child;
    }

    @NonNull Group create() {
        runtime.configure(configuration);
        runtime.delegation().createGroup("brief");
        return runtime.registry().snapshot().values().iterator().next();
    }

    public void close() {
        runtime.close();
    }

    static <T> @NonNull T required(T value) {
        if (value == null) throw new AssertionError("Required test value was null");
        return value;
    }
}
