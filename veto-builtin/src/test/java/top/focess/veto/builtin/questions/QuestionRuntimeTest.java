package top.focess.veto.builtin.questions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static top.focess.veto.builtin.questions.QuestionTestSupport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;

class QuestionRuntimeTest {

    @Test
    void publishesAndResolvesOneQuestionBatch() {
        QuestionRuntime registry = new QuestionRuntime(host());
        Question question =
                new Question(
                        "Format",
                        "format",
                        "Which format?",
                        List.of(
                                new Option("Markdown", "Formatted output."),
                                new Option("Text", "Plain output.")));
        var answer = registry.register(invocation("agent", "call-1"), List.of(question));

        assertEquals(1, registry.pendingFor(scope("agent")).size());
        assertTrue(registry.answer(scope("agent"), "call-1", Map.of("format", "Markdown")));
        assertEquals("Markdown", answer.join().answers().get("format"));
        assertTrue(registry.pendingFor(scope("agent")).isEmpty());
    }

    @Test
    void liveActionsCarryOrderedCompleteAgentSnapshotsAndMatchFrontendResponses() throws Exception {
        var host = mock(PluginHost.class);
        var runtime = new QuestionRuntime(host);
        var frontend = new QuestionsFrontend(runtime);
        assertTrue(frontend.module().contains("export"));
        var first = runtime.register(invocation("agent", "call-b"), List.of(question()));
        var other = runtime.register(invocation("other", "other-call"), List.of(question()));
        var second = runtime.register(invocation("agent", "call-a"), List.of(question()));
        var listed = frontend.handle(scope("agent"), "list", new JsonValue.ObjectValue(Map.of()));
        var answered =
                frontend.handle(
                        scope("agent"),
                        "answer",
                        new JsonValue.ObjectValue(
                                Map.of(
                                        "callId", new JsonValue.StringValue("call-b"),
                                        "answers",
                                                new JsonValue.ObjectValue(
                                                        Map.of(
                                                                "choice",
                                                                new JsonValue.StringValue(
                                                                        "First"))))));
        var cancelled =
                frontend.handle(
                        scope("agent"),
                        "cancel",
                        new JsonValue.ObjectValue(
                                Map.of("callId", new JsonValue.StringValue("call-a"))));

        var states = ArgumentCaptor.forClass(JsonValue.ObjectValue.class);
        var scopes = ArgumentCaptor.forClass(Scope.SessionScope.class);
        verify(host, times(5)).publish(scopes.capture(), eq("questions_changed"), states.capture());
        verifyNoMoreInteractions(host);
        assertTrue(scopes.getAllValues().stream().allMatch(scope("agent").sessionScope()::equals));
        var events = states.getAllValues();
        assertEquals(events.get(2), listed);
        assertEquals(events.get(3), answered);
        assertEquals(events.get(4), cancelled);
        for (int i = 0; i < events.size(); i++) {
            var event = new ObjectMapper().valueToTree(JsonValues.toMap(events.get(i)));
            assertEquals(i + 1, event.path("revision").asLong());
            assertEquals(i == 1 ? "other" : "agent", event.path("agentId").asText());
        }
        var registered = new ObjectMapper().valueToTree(JsonValues.toMap(events.get(2)));
        assertEquals(2, registered.path("items").size());
        assertEquals("call-a", registered.path("items").path(0).path("callId").asText());
        assertEquals("call-b", registered.path("items").path(1).path("callId").asText());
        assertEquals(
                new ObjectMapper().valueToTree(question()),
                registered.path("items").path(0).path("questions").path(0));
        var remaining = new ObjectMapper().valueToTree(JsonValues.toMap(events.get(3)));
        assertEquals(1, remaining.path("items").size());
        assertEquals("call-a", remaining.path("items").path(0).path("callId").asText());
        assertEquals(List.of(), JsonValues.toMap(events.get(4)).get("items"));
        assertEquals(Map.of("choice", "First"), first.join().answers());
        assertTrue(second.join().cancelled());
        assertFalse(other.isDone());
        runtime.close();
    }

    @Test
    void oversizedStateDoesNotLeaveAnUnpublishedWaiter() {
        var host = mock(PluginHost.class);
        var runtime = new QuestionRuntime(host);
        var existing = runtime.register(invocation("agent", "existing"), List.of(question()));
        var oversized = new Question("Choice", "q".repeat(65536), "Which?", question().options());
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.register(invocation("agent", "oversized"), List.of(oversized)));
        assertFalse(existing.isDone());
        assertEquals("existing", runtime.pendingFor(scope("agent")).getFirst().callId());
        assertEquals(1, runtime.pendingFor(scope("agent")).size());
        var states = ArgumentCaptor.forClass(JsonValue.ObjectValue.class);
        verify(host, times(2))
                .publish(
                        eq(scope("agent").sessionScope()),
                        eq("questions_changed"),
                        states.capture());
        assertEquals(runtime.snapshot(scope("agent")), states.getAllValues().getLast());
        runtime.close();
    }

    @Test
    void unexpectedPublicationFailureRollsBackRegistration() {
        var host = mock(PluginHost.class);
        var failure = new UnsupportedOperationException("Publication failed");
        doThrow(failure)
                .doNothing()
                .when(host)
                .publish(eq(scope("agent").sessionScope()), eq("questions_changed"), any());
        var runtime = new QuestionRuntime(host);
        assertSame(
                failure,
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> runtime.register(invocation("agent", "call"), List.of(question()))));
        assertTrue(runtime.pendingFor(scope("agent")).isEmpty());
        assertEquals(List.of(), JsonValues.toMap(runtime.snapshot(scope("agent"))).get("items"));
    }

    @Test
    void duplicateRegistrationCannotReplaceExistingWaiter() {
        QuestionRuntime registry = new QuestionRuntime(host());
        var first = registry.register(invocation("agent", "call"), List.of(question()));
        assertThrows(
                IllegalStateException.class,
                () -> registry.register(invocation("agent", "call"), List.of(question())));
        assertFalse(first.isDone());
        assertTrue(registry.answer(scope("agent"), "call", Map.of("choice", "First")));
        assertEquals("First", first.join().answers().get("choice"));
        var replacement = registry.register(invocation("agent", "call"), List.of(question()));
        first.cancel(false);
        assertEquals(1, registry.pendingFor(scope("agent")).size());
        replacement.cancel(false);
        assertTrue(registry.pendingFor(scope("agent")).isEmpty());
    }

    @Test
    void agentAndCallIdsAreIndependentEvenWhenTheyContainSeparators() {
        QuestionRuntime registry = new QuestionRuntime(host());
        var first = registry.register(invocation("a|b", "c"), List.of(question()));
        var second = registry.register(invocation("a", "b|c"), List.of(question()));
        assertFalse(registry.answer(scope("a"), "c", Map.of("choice", "Wrong caller")));
        assertFalse(registry.cancel(scope("a|b"), "b|c"));
        assertTrue(registry.answer(scope("a|b"), "c", Map.of("choice", "First")));
        assertFalse(second.isDone());
        assertTrue(registry.cancel(scope("a"), "b|c"));
        assertEquals("First", first.join().answers().get("choice"));
        assertTrue(second.join().cancelled());
        assertTrue(registry.pendingFor(scope("a")).isEmpty());
        assertTrue(registry.pendingFor(scope("a|b")).isEmpty());
    }

    @Test
    void rejectsIncompleteUnknownBlankAndOversizedAnswersWithoutConsumingBatch() {
        var host = mock(PluginHost.class);
        QuestionRuntime registry = new QuestionRuntime(host);
        var pending = registry.register(invocation("agent", "call"), List.of(question()));
        assertFalse(registry.answer(scope("agent"), "call", Map.of()));
        assertFalse(registry.answer(scope("agent"), "call", Map.of("unknown", "First")));
        assertFalse(
                registry.answer(
                        scope("agent"), "call", Map.of("choice", "First", "extra", "Second")));
        assertFalse(registry.answer(scope("agent"), "call", Map.of("choice", " ")));
        assertFalse(registry.answer(scope("agent"), "call", Map.of("choice", "x".repeat(501))));
        verify(host).publish(eq(scope("agent").sessionScope()), eq("questions_changed"), any());
        verifyNoMoreInteractions(host);
        assertFalse(pending.isDone());
        String unicode = "\uD83D\uDE00".repeat(500);
        assertTrue(registry.answer(scope("agent"), "call", Map.of("choice", unicode)));
        assertEquals(unicode, pending.join().answers().get("choice"));
        assertFalse(registry.answer(scope("agent"), "call", Map.of("choice", "Second")));
    }

    @Test
    void snapshotsQuestionsOptionsAndCompletedAnswers() {
        QuestionRuntime registry = new QuestionRuntime(host());
        var options = new ArrayList<>(question().options());
        List<Question> questions =
                new ArrayList<>(List.of(new Question("Choice", "choice", "Which?", options)));
        var pending = registry.register(invocation("agent", "call"), questions);
        questions.clear();
        options.clear();
        Object exposedQuestions = registry.pendingFor(scope("agent")).getFirst().questions();
        assertEquals(
                List.of(new Question("Choice", "choice", "Which?", question().options())),
                exposedQuestions);
        assertFalse(registry.answer(scope("agent"), "call", Map.of()));
        Map<String, String> answers = new LinkedHashMap<>(Map.of("choice", "Custom"));
        assertTrue(registry.answer(scope("agent"), "call", answers));
        answers.put("choice", "Changed");
        assertEquals("Custom", pending.join().answers().get("choice"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> pending.join().answers().put("choice", "Changed"));
    }

    @Test
    void concurrentAnswerAndCancelHaveExactlyOneWinner() throws Exception {
        var host = mock(PluginHost.class);
        QuestionRuntime registry = new QuestionRuntime(host);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int iteration = 0; iteration < 50; iteration++) {
                var pending = registry.register(invocation("agent", "call"), List.of(question()));
                var start = new CountDownLatch(1);
                var answer =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return registry.answer(
                                            scope("agent"), "call", Map.of("choice", "First"));
                                });
                var cancel =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return registry.cancel(scope("agent"), "call");
                                });
                start.countDown();
                boolean answered = answer.get(2, TimeUnit.SECONDS);
                boolean cancelled = cancel.get(2, TimeUnit.SECONDS);
                assertTrue(answered ^ cancelled);
                var result = pending.get(2, TimeUnit.SECONDS);
                assertEquals(cancelled, result.cancelled());
                assertEquals(answered ? Map.of("choice", "First") : Map.of(), result.answers());
                assertTrue(registry.pendingFor(scope("agent")).isEmpty());
            }
            var states = ArgumentCaptor.forClass(JsonValue.ObjectValue.class);
            verify(host, times(100))
                    .publish(
                            eq(scope("agent").sessionScope()),
                            eq("questions_changed"),
                            states.capture());
            var events = states.getAllValues();
            for (int index = 0; index < events.size(); index++)
                assertEquals(
                        BigDecimal.valueOf(index + 1),
                        JsonValues.toMap(events.get(index)).get("revision"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static @NonNull Question question() {
        return new Question(
                "Choice",
                "choice",
                "Which option?",
                List.of(
                        new Option("First (Recommended)", "Default."),
                        new Option("Second", "Alternative.")));
    }
}
