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
import top.focess.veto.api.plugin.contract.FrontendContribution;
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
                new FrontendContribution.Scope(
                        invocation.owner(), invocation.sessionId(), invocation.agentId());
        var contribution = new TasksFrontend(tasks).contribution();
        var handler = contribution.handler();
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
                            new FrontendContribution.Scope(
                                    "other", scope.sessionId(), scope.agentId()),
                            new FrontendContribution.Scope(
                                    scope.ownerId(), UUID.randomUUID().toString(), scope.agentId()),
                            new FrontendContribution.Scope(
                                    scope.ownerId(), scope.sessionId(), "other"))) {
                var list =
                        object(handler.handle(wrong, "list", new JsonValue.ObjectValue(Map.of())));
                assertEquals(
                        new JsonValue.NumberValue(BigDecimal.ZERO), list.values().get("total"));
                assertThrows(
                        PluginFailure.class, () -> handler.handle(wrong, "stopOrRemove", args));
            }
            assertThrows(
                    PluginFailure.class,
                    () ->
                            handler.handle(
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
            var stopped = object(handler.handle(scope, "stopOrRemove", args));
            assertEquals(new JsonValue.StringValue("stopped"), stopped.values().get("status"));
            tasks.awaitExit(BackgroundTasks.Scope.from(invocation), info.taskId());
            var pageArgs = new HashMap<String, JsonValue>(args.values());
            pageArgs.put("limit", new JsonValue.NumberValue(BigDecimal.valueOf(3)));
            var page = object(handler.handle(scope, "output", new JsonValue.ObjectValue(pageArgs)));
            assertEquals(new JsonValue.StringValue("abc"), page.values().get("text"));
            assertEquals(
                    new JsonValue.NumberValue(BigDecimal.valueOf(3)),
                    page.values().get("nextOffset"));
            pageArgs.put("offset", new JsonValue.NumberValue(BigDecimal.valueOf(3)));
            assertEquals(
                    new JsonValue.StringValue("def"),
                    object(handler.handle(scope, "output", new JsonValue.ObjectValue(pageArgs)))
                            .values()
                            .get("text"));
            var removed = object(handler.handle(scope, "stopOrRemove", args));
            assertEquals(new JsonValue.StringValue("removed"), removed.values().get("status"));
            assertThrows(PluginFailure.class, () -> handler.handle(scope, "output", args));
        } finally {
            tasks.close();
        }
    }

    private static JsonValue.@NonNull ObjectValue object(@NonNull JsonValue value) {
        if (value instanceof JsonValue.ObjectValue object) return object;
        throw new AssertionError("Expected object");
    }
}
