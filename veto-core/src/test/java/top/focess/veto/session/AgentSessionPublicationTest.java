package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.vault.TestUsers;

class AgentSessionPublicationTest {
    @Test
    void publicationReachesOnlyAttachedOwnerConnections() {
        var runtime = TestAgentSessions.create();
        var session = UUID.randomUUID().toString();
        List<Delta> first = new CopyOnWriteArrayList<>();
        List<Delta> second = new CopyOnWriteArrayList<>();
        var owner =
                new TestConnection(
                        TestUsers.OWNER,
                        frame -> {
                            if (frame instanceof Delta delta) first.add(delta);
                        });
        runtime.connectBrowser(owner.connection());
        runtime.connectBrowser(
                new TestConnection(
                                TestUsers.OWNER,
                                frame -> {
                                    if (frame instanceof Delta delta) second.add(delta);
                                })
                        .connection());
        runtime.connectBrowser(
                new TestConnection(UUID.randomUUID(), frame -> fail("Foreign output"))
                        .connection());
        var scope = new Scope.AgentScope(TestUsers.OWNER, session, "agent");
        runtime.session(scope.sessionScope()).send(new Delta(scope, new Delta.Message("hello", 0)));
        runtime.session(scope.sessionScope())
                .send(new Delta(scope, new Delta.Thought("thinking", 0)));
        assertEquals(2, first.size());
        assertEquals(first, second);
        assertEquals("hello", ((Delta.Message) first.getFirst().data()).text());
        assertEquals(1L, first.getFirst().sequence());
        assertEquals(2L, first.getLast().sequence());
        runtime.disconnect(owner.connection());
        runtime.session(scope.sessionScope())
                .send(new Delta(scope, new Delta.Message("after disconnect", 0)));
        assertEquals(2, first.size());
        assertEquals(3, second.size());
    }

    @Test
    void sequencesIncreaseIndependentlyForEachSession() {
        var runtime = TestAgentSessions.create();
        var first = new Scope.AgentScope(TestUsers.OWNER, UUID.randomUUID().toString(), "first");
        var second = new Scope.AgentScope(TestUsers.OWNER, UUID.randomUUID().toString(), "second");
        List<Delta> received = new CopyOnWriteArrayList<>();
        runtime.connectBrowser(
                new TestConnection(
                                TestUsers.OWNER,
                                frame -> {
                                    if (frame instanceof Delta delta) received.add(delta);
                                })
                        .connection());
        for (int i = 0; i < 10; i++) {
            runtime.session(first.sessionScope())
                    .send(new Delta(first, new Delta.Thought("step " + i, 0)));
            runtime.session(second.sessionScope())
                    .send(new Delta(second, new Delta.Message("step " + i, 0)));
        }
        assertEquals(20, received.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, received.get(2 * i).sequence());
            assertEquals(i + 1L, received.get(2 * i + 1).sequence());
        }
    }
}
