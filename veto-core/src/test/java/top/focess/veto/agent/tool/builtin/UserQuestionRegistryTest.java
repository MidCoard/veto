package top.focess.veto.agent.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;

class UserQuestionRegistryTest {

    @Test
    void publishesAndResolvesOneQuestionBatch() {
        UserQuestionRegistry registry = new UserQuestionRegistry();
        AskUserTool.Question question =
                new AskUserTool.Question(
                        "Format",
                        "format",
                        "Which format?",
                        List.of(
                                new AskUserTool.Option("Markdown", "Formatted output."),
                                new AskUserTool.Option("Text", "Plain output.")));
        var answer = registry.register("agent", "call-1", List.of(question));

        assertEquals(1, registry.pendingFor("agent").size());
        assertTrue(registry.answer("agent", "call-1", Map.of("format", "Markdown")));
        assertEquals("Markdown", answer.join().answers().get("format"));
        assertTrue(registry.pendingFor("agent").isEmpty());
    }

    @Test
    void duplicateRegistrationCannotReplaceExistingWaiter() {
        UserQuestionRegistry registry = new UserQuestionRegistry();
        var first = registry.register("agent", "call", List.of(question()));
        assertThrows(
                ToolDocs.nonNullClass(IllegalStateException.class),
                () -> registry.register("agent", "call", List.of(question())));
        assertFalse(first.isDone());
        assertTrue(registry.answer("agent", "call", Map.of("choice", "First")));
        assertEquals("First", first.join().answers().get("choice"));
        var replacement = registry.register("agent", "call", List.of(question()));
        first.cancel(false);
        assertEquals(1, registry.pendingFor("agent").size());
        replacement.cancel(false);
        assertTrue(registry.pendingFor("agent").isEmpty());
    }

    @Test
    void agentAndCallIdsAreIndependentEvenWhenTheyContainSeparators() {
        UserQuestionRegistry registry = new UserQuestionRegistry();
        var first = registry.register("a|b", "c", List.of(question()));
        var second = registry.register("a", "b|c", List.of(question()));
        assertFalse(registry.answer("a", "c", Map.of("choice", "Wrong caller")));
        assertFalse(registry.cancel("a|b", "b|c"));
        assertTrue(registry.answer("a|b", "c", Map.of("choice", "First")));
        assertFalse(second.isDone());
        assertTrue(registry.cancel("a", "b|c"));
        assertEquals("First", first.join().answers().get("choice"));
        assertTrue(second.join().cancelled());
        assertTrue(registry.pendingFor("a").isEmpty());
        assertTrue(registry.pendingFor("a|b").isEmpty());
    }

    @Test
    void rejectsIncompleteUnknownBlankAndOversizedAnswersWithoutConsumingBatch() {
        UserQuestionRegistry registry = new UserQuestionRegistry();
        var pending = registry.register("agent", "call", List.of(question()));
        assertFalse(registry.answer("agent", "call", Map.of()));
        assertFalse(registry.answer("agent", "call", Map.of("unknown", "First")));
        assertFalse(registry.answer("agent", "call", Map.of("choice", "First", "extra", "Second")));
        assertFalse(registry.answer("agent", "call", Map.of("choice", " ")));
        assertFalse(registry.answer("agent", "call", Map.of("choice", "x".repeat(501))));
        assertFalse(pending.isDone());
        String unicode = "\uD83D\uDE00".repeat(500);
        assertTrue(registry.answer("agent", "call", Map.of("choice", unicode)));
        assertEquals(unicode, pending.join().answers().get("choice"));
        assertFalse(registry.answer("agent", "call", Map.of("choice", "Second")));
    }

    @Test
    void snapshotsQuestionsOptionsAndCompletedAnswers() {
        UserQuestionRegistry registry = new UserQuestionRegistry();
        var options = new ArrayList<>(question().options());
        List<AskUserTool.Question> questions =
                new ArrayList<>(
                        List.of(new AskUserTool.Question("Choice", "choice", "Which?", options)));
        var pending = registry.register("agent", "call", questions);
        questions.clear();
        options.clear();
        Object exposedQuestions = registry.pendingFor("agent").getFirst().get("questions");
        assertEquals(
                List.of(
                        new AskUserTool.Question(
                                "Choice", "choice", "Which?", question().options())),
                exposedQuestions);
        assertFalse(registry.answer("agent", "call", Map.of()));
        Map<String, String> answers = new LinkedHashMap<>(Map.of("choice", "Custom"));
        assertTrue(registry.answer("agent", "call", answers));
        answers.put("choice", "Changed");
        assertEquals("Custom", pending.join().answers().get("choice"));
        assertThrows(
                ToolDocs.nonNullClass(UnsupportedOperationException.class),
                () -> pending.join().answers().put("choice", "Changed"));
    }

    @Test
    void concurrentAnswerAndCancelHaveExactlyOneWinner() throws Exception {
        UserQuestionRegistry registry = new UserQuestionRegistry();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int iteration = 0; iteration < 50; iteration++) {
                var pending = registry.register("agent", "call", List.of(question()));
                var start = new CountDownLatch(1);
                var answer =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return registry.answer(
                                            "agent", "call", Map.of("choice", "First"));
                                });
                var cancel =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return registry.cancel("agent", "call");
                                });
                start.countDown();
                boolean answered = answer.get(2, TimeUnit.SECONDS);
                boolean cancelled = cancel.get(2, TimeUnit.SECONDS);
                assertTrue(answered ^ cancelled);
                var result = pending.get(2, TimeUnit.SECONDS);
                assertEquals(cancelled, result.cancelled());
                assertEquals(answered ? Map.of("choice", "First") : Map.of(), result.answers());
                assertTrue(registry.pendingFor("agent").isEmpty());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static AskUserTool.@NonNull Question question() {
        return new AskUserTool.Question(
                "Choice",
                "choice",
                "Which option?",
                List.of(
                        new AskUserTool.Option("First (Recommended)", "Default."),
                        new AskUserTool.Option("Second", "Alternative.")));
    }
}
