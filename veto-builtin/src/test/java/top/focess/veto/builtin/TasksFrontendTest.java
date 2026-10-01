package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.builtin.process.BackgroundTasks;
import top.focess.veto.builtin.process.TasksFrontend;

@Timeout(10)
class TasksFrontendTest {
    @Test
    void actualContributionEnforcesScopeInstanceAndExplicitOutputPages() throws Exception {
        var process = new BackgroundTasksTest.Running("abcdef\n");
        var tasks = new BackgroundTasks(() -> new BackgroundTasksTest.Host(process));
        var info = tasks.start();
        var invocation = process.invocation();
        var scope =
                new Scope.AgentScope(
                        invocation.owner(), invocation.sessionId(), invocation.agentId());
        var contribution = new TasksFrontend(tasks);
        var args =
                new JsonValue.ObjectValue(
                        Map.of(
                                "taskId",
                                new JsonValue.StringValue(info.taskId()),
                                "taskInstanceId",
                                new JsonValue.StringValue(process.id().toString())));
        try {
            for (var wrong :
                    List.of(
                            new Scope.AgentScope("other", scope.session(), scope.agent()),
                            new Scope.AgentScope(
                                    scope.owner(), UUID.randomUUID().toString(), scope.agent()),
                            new Scope.AgentScope(scope.owner(), scope.session(), "other"))) {
                var list =
                        object(
                                contribution.handle(
                                        wrong, "list", new JsonValue.ObjectValue(Map.of())));
                assertEquals(
                        new JsonValue.NumberValue(BigDecimal.ZERO), list.values().get("total"));
                assertThrows(
                        PluginFailure.class,
                        () -> contribution.handle(wrong, "stopOrRemove", args));
            }
            assertThrows(
                    PluginFailure.class,
                    () ->
                            contribution.handle(
                                    scope,
                                    "stopOrRemove",
                                    new JsonValue.ObjectValue(
                                            Map.of(
                                                    "taskId",
                                                    new JsonValue.StringValue(info.taskId()),
                                                    "taskInstanceId",
                                                    new JsonValue.StringValue(
                                                            UUID.randomUUID().toString())))));
            assertTrue(process.isAlive());
            var stopped = object(contribution.handle(scope, "stopOrRemove", args));
            assertEquals(new JsonValue.StringValue("stopped"), stopped.values().get("status"));
            tasks.awaitExit(invocation.scope(), info.taskId());
            var pageArgs = new HashMap<String, JsonValue>(args.values());
            pageArgs.put("limit", new JsonValue.NumberValue(BigDecimal.valueOf(3)));
            var page =
                    object(
                            contribution.handle(
                                    scope, "output", new JsonValue.ObjectValue(pageArgs)));
            assertEquals(new JsonValue.StringValue("abc"), page.values().get("text"));
            assertEquals(
                    new JsonValue.NumberValue(BigDecimal.valueOf(3)),
                    page.values().get("nextOffset"));
            pageArgs.put("offset", new JsonValue.NumberValue(BigDecimal.valueOf(3)));
            assertEquals(
                    new JsonValue.StringValue("def"),
                    object(
                                    contribution.handle(
                                            scope, "output", new JsonValue.ObjectValue(pageArgs)))
                            .values()
                            .get("text"));
            var removed = object(contribution.handle(scope, "stopOrRemove", args));
            assertEquals(new JsonValue.StringValue("removed"), removed.values().get("status"));
            assertThrows(PluginFailure.class, () -> contribution.handle(scope, "output", args));
        } finally {
            tasks.close();
        }
    }

    private static JsonValue.@NonNull ObjectValue object(@NonNull JsonValue value) {
        if (value instanceof JsonValue.ObjectValue object) return object;
        throw new AssertionError("Expected object");
    }
}
