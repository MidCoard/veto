package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.builtin.group.GroupTools.CreateGroup;
import top.focess.veto.builtin.group.GroupTools.DisbandGroup;
import top.focess.veto.builtin.group.GroupTools.InspectGroup;
import top.focess.veto.builtin.group.GroupTools.PostMessage;

/** Actual tool bodies and plugin runtime, with only the public host boundary substituted. */
class GroupToolsWiringTest {
    @Test
    void taskRequestComesFromTrustedContextAndSurvivesRetry() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            var registry = fixture.runtime.registry();
            var board = group.blackboard();
            registry.put(group.withMate("mate", "review"));
            var tool = new CollaborationTools.CreateTask(fixture.runtime.operations());
            assertTrue(
                    tool.execute(
                                    new CollaborationTools.CreateTask.Args(
                                            "task", "Review", "mate", List.of()))
                            .contains("Task registered"));
            var orchestrator = new GroupOrchestrator(registry, board);
            var running = GroupTestHost.required(orchestrator.tick(group.groupId()));
            assertEquals("request-one", running.dag().nodes().getFirst().requestId());
            GroupTestMessages.feedback(board, group.groupId(), "mate", "task", "retry");
            orchestrator.tick(group.groupId());
            orchestrator.replanFailed(group.groupId(), "task");
            var retried = GroupTestHost.required(orchestrator.tick(group.groupId()));
            assertEquals("request-one", retried.dag().nodes().getFirst().requestId());
            assertNotEquals(
                    running.dag().nodes().getFirst().dispatchId(),
                    retried.dag().nodes().getFirst().dispatchId());
        }
    }

    @Test
    void validCallerPermitCannotDisbandAnotherAgentsGroup() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            fixture.caller =
                    new PluginHost.Invocation(
                            "owner", fixture.scope.sessionId(), "other-agent", null, "test-call");
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            new DisbandGroup(fixture.runtime.operations())
                                    .execute(new DisbandGroup.Args()));
            assertTrue(
                    GroupTestHost.required(fixture.runtime.registry().get(group.groupId()))
                            .isActive());
        }
    }

    @Test
    void createGroupRegistersEmptyGroupAndRequestsTransform() {
        try (var fixture = new GroupTestHost()) {
            fixture.runtime.configure(fixture.configuration);
            assertEquals(
                    "",
                    new CreateGroup(fixture.runtime.delegation())
                            .execute(new CreateGroup.Args("do the thing")));
            var group = fixture.runtime.registry().snapshot().values().iterator().next();
            assertTrue(group.dag().nodes().isEmpty());
            assertTrue(group.mates().isEmpty());
            assertEquals("leader", group.leaderId());
            assertEquals("owner", group.owner());
            var intent = GroupTestHost.required(fixture.runtime.configure(fixture.configuration));
            assertEquals("LEADER", intent.profile().label());
            assertEquals("TOP", intent.profile().tier());
            assertEquals("runtime-leader", GroupTestHost.required(intent.transition()).prompt());
        }
    }

    @Test
    void createGroupRefusesBlankBrief() {
        try (var fixture = new GroupTestHost()) {
            fixture.runtime.configure(fixture.configuration);
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            new CreateGroup(fixture.runtime.delegation())
                                    .execute(new CreateGroup.Args("   ")));
            assertTrue(fixture.runtime.registry().snapshot().isEmpty());
            assertNull(
                    GroupTestHost.required(fixture.runtime.configure(fixture.configuration))
                            .transition());
        }
    }

    @Test
    void disbandGroupTearsDownGroupAndRequestsReverseTransform() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            new DisbandGroup(fixture.runtime.operations()).execute(new DisbandGroup.Args());
            assertEquals(
                    GroupState.DISBANDED,
                    GroupTestHost.required(fixture.runtime.registry().get(group.groupId()))
                            .state());
            var intent = GroupTestHost.required(fixture.runtime.configure(fixture.configuration));
            assertEquals("STANDALONE", intent.profile().label());
            assertEquals("runtime-disband", GroupTestHost.required(intent.transition()).prompt());
        }
    }

    @Test
    void disbandGroupRefusesWithoutActiveGroup() {
        try (var fixture = new GroupTestHost()) {
            fixture.runtime.configure(fixture.configuration);
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            new DisbandGroup(fixture.runtime.operations())
                                    .execute(new DisbandGroup.Args()));
            assertNull(
                    GroupTestHost.required(fixture.runtime.configure(fixture.configuration))
                            .transition());
        }
    }

    @Test
    void postMessageRecordsLeaderNote() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            var post = new PostMessage(fixture.runtime.operations());
            assertEquals(
                    "posted",
                    post.execute(
                            new PostMessage.Args(
                                    BlackboardMessage.MessageType.FEEDBACK, "LEADER", "oops")));
            var message = group.blackboard().readFor(group.groupId(), "LEADER").getFirst();
            assertEquals("LEADER", message.senderId());
            assertEquals("oops", message.payload());
            assertEquals(BlackboardMessage.MessageType.FEEDBACK, message.type());
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            post.execute(
                                    new PostMessage.Args(
                                            BlackboardMessage.MessageType.TASK_DISPATCH,
                                            "mate",
                                            "hidden:work")));
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            post.execute(
                                    new PostMessage.Args(
                                            BlackboardMessage.MessageType.FEEDBACK,
                                            "mate",
                                            "hidden")));
            assertTrue(group.blackboard().readFor(group.groupId(), "mate").isEmpty());
        }
    }

    @Test
    void postMessageRefusesWithoutActiveGroup() {
        try (var fixture = new GroupTestHost()) {
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            new PostMessage(fixture.runtime.operations())
                                    .execute(
                                            new PostMessage.Args(
                                                    BlackboardMessage.MessageType.FEEDBACK,
                                                    "LEADER",
                                                    "note")));
        }
    }

    @Test
    void postMessageRefusesDisbandedGroup() {
        try (var fixture = new GroupTestHost()) {
            fixture.create();
            fixture.runtime.operations().disband("finished");
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            new PostMessage(fixture.runtime.operations())
                                    .execute(
                                            new PostMessage.Args(
                                                    BlackboardMessage.MessageType.FEEDBACK,
                                                    "LEADER",
                                                    "note")));
        }
    }

    @Test
    void inspectGroupReturnsOnlyNewMateReportsAndCursor() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            fixture.runtime.registry().put(group.withMate("mate", "review"));
            group.blackboard()
                    .post(
                            new BlackboardMessage(
                                    "report",
                                    group.groupId(),
                                    "mate",
                                    "LEADER",
                                    BlackboardMessage.MessageType.FEEDBACK,
                                    "task:feedback:review",
                                    0));
            group.blackboard()
                    .post(
                            new BlackboardMessage(
                                    "foreign-report",
                                    group.groupId(),
                                    "unknown-mate",
                                    "LEADER",
                                    BlackboardMessage.MessageType.FEEDBACK,
                                    "not a group member",
                                    0));
            var messages = fixture.runtime.operations().messages(0);
            assertEquals(1, messages.size());
            assertEquals("report", messages.getFirst().messageId());
            assertEquals(1L, messages.getFirst().turnSeq());
            assertTrue(fixture.runtime.operations().messages(1).isEmpty());
            String rendered =
                    new InspectGroup(fixture.runtime.operations())
                            .execute(new InspectGroup.Args(0L, 0));
            assertTrue(rendered.contains("group-inspect"));
        }
    }

    @Test
    void removeMateToolRejectsUnfinishedWorkAndNonLeader() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            fixture.runtime.registry().put(group.withMate("mate", "review"));
            fixture.runtime.operations().createTask("task", "work", "mate", "review", Set.of());
            var tool = new CollaborationTools.RemoveMate(fixture.runtime.operations());
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () -> tool.execute(new CollaborationTools.RemoveMate.Args("mate")));
            fixture.caller =
                    new PluginHost.Invocation(
                            "owner", fixture.scope.sessionId(), "mate", null, "test-call");
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () -> tool.execute(new CollaborationTools.RemoveMate.Args("mate")));
        }
    }

    @Test
    void removeMateRejectsForeignOwnerAndSession() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            fixture.runtime.registry().put(group.withMate("mate", "review"));
            for (var caller :
                    List.of(
                            new PluginHost.Invocation(
                                    "foreign",
                                    fixture.scope.sessionId(),
                                    "leader",
                                    null,
                                    "test-call"),
                            new PluginHost.Invocation(
                                    "owner",
                                    UUID.randomUUID().toString(),
                                    "leader",
                                    null,
                                    "test-call"))) {
                fixture.caller = caller;
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                new CollaborationTools.RemoveMate(fixture.runtime.operations())
                                        .execute(new CollaborationTools.RemoveMate.Args("mate")));
            }
            assertTrue(
                    GroupTestHost.required(fixture.runtime.registry().get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
        }
    }
}
