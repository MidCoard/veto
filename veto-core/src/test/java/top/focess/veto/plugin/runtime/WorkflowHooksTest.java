package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;

class WorkflowHooksTest {
    private static WorkflowHook.@NonNull Context scope(boolean cancelled) {
        return new WorkflowHook.Context("owner", "session", "agent", () -> cancelled);
    }

    @Test
    void orderingSelectionCancellationAndLifecycleAreEnforced() throws Exception {
        var calls = new AtomicInteger();
        WorkflowHook first =
                new WorkflowHook() {
                    @Override
                    public @NonNull String beforeInput(
                            @NonNull Context context, @NonNull String text) {
                        calls.incrementAndGet();
                        return text + "A";
                    }
                };
        WorkflowHook second =
                new WorkflowHook() {
                    @Override
                    public @NonNull String beforeInput(
                            @NonNull Context context, @NonNull String text) {
                        return text + "B";
                    }
                };
        var point = StandardContributionPoints.WORKFLOW;
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                new Contribution<>(
                                        point,
                                        "second",
                                        second,
                                        Set.of(),
                                        Set.of(new ContributionId("fixture.workflow:first"))),
                                Contribution.of(point, "first", first)))) {
            assertEquals(
                    "AB",
                    fixture.sessions.workflow(
                            scope(false),
                            "",
                            (hook, text) -> hook.beforeInput(scope(false), text)));
            assertEquals(1, calls.get());
            assertThrows(
                    ToolDocs.nonNullClass(IllegalStateException.class),
                    () ->
                            fixture.sessions.workflow(
                                    scope(true),
                                    "",
                                    (hook, text) -> hook.beforeInput(scope(true), text)));
            assertEquals(1, calls.get());
            SessionPlugins none = spy(fixture.sessions);
            doReturn(List.of()).when(none).bindings("session");
            assertEquals(
                    "unchanged",
                    none.workflow(
                            scope(false),
                            "unchanged",
                            (hook, text) -> hook.beforeInput(scope(false), text)));
            fixture.runtime.close();
            assertThrows(
                    ToolDocs.nonNullClass(IllegalStateException.class),
                    () ->
                            fixture.sessions.workflow(
                                    scope(false),
                                    "",
                                    (hook, text) -> hook.beforeInput(scope(false), text)));
        }
    }

    @Test
    void failuresDoNotLeakPluginPayloadOrRunFollowingHooks() throws Exception {
        var calls = new AtomicInteger();
        WorkflowHook bad =
                new WorkflowHook() {
                    @Override
                    public @NonNull String beforeInput(
                            @NonNull Context context, @NonNull String text) {
                        throw new IllegalStateException("sensitive payload");
                    }
                };
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.WORKFLOW, "bad", bad)))) {
            var error =
                    assertThrows(
                            ToolDocs.nonNullClass(IllegalStateException.class),
                            () ->
                                    fixture.sessions.workflow(
                                            scope(false),
                                            "",
                                            (hook, text) -> {
                                                String value = hook.beforeInput(scope(false), text);
                                                calls.incrementAndGet();
                                                return value;
                                            }));
            assertEquals("Workflow hook unavailable", error.getMessage());
            assertNull(error.getCause());
            assertEquals(0, calls.get());
        }
    }
}
