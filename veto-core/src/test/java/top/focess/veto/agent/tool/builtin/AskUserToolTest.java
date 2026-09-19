package top.focess.veto.agent.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.capability.UserInteractionCapabilityImpl;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrorCode;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolResultStatus;
import top.focess.veto.util.Nullness;

@Timeout(10)
class AskUserToolTest {

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 5})
    void acceptsPlainLabelsAndTwoToFiveOptions(int count) throws Exception {
        var options = new ArrayList<AskUserTool.Option>();
        for (int i = 0; i < count; i++)
            options.add(new AskUserTool.Option("Choice " + i, "Description " + i));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result =
                    executor.submit(
                            () ->
                                    CapabilityTestCalls.execute(
                                            tool,
                                            new AskUserTool.Args(
                                                    List.of(
                                                            new AskUserTool.Question(
                                                                    "Scope",
                                                                    "scope",
                                                                    "Choose scope",
                                                                    options)))));
            assertTrue(
                    registry.answer(
                            "test-agent",
                            awaitPending(),
                            Map.of("scope", "Choice " + (count - 1))));
            assertEquals(
                    "Choice " + (count - 1),
                    new ObjectMapper()
                            .readTree(result.get(2, TimeUnit.SECONDS))
                            .path("answers")
                            .path("scope")
                            .asText());
        }
    }

    @Test
    void identifiesSixOptionQuestionAndNeverPublishesAPartialBatch() {
        var valid = question("language");
        var invalid =
                new AskUserTool.Question(
                        "Scope",
                        "scope",
                        "Choose the scope",
                        List.of(
                                valid.options().getFirst(),
                                valid.options().getLast(),
                                new AskUserTool.Option("Third", "Third choice"),
                                new AskUserTool.Option("Fourth", "Fourth choice"),
                                new AskUserTool.Option("Fifth", "Fifth choice"),
                                new AskUserTool.Option("Sixth", "Sixth choice")));
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool, new AskUserTool.Args(List.of(valid, invalid))));
        assertEquals(ToolErrorCode.INVALID_QUESTIONS, error.errorCode());
        assertTrue(Nullness.requireNonNull(error.getMessage()).contains("'scope' has 6 options"));
        assertTrue(Nullness.requireNonNull(error.getMessage()).contains("No questions were sent"));
        assertTrue(registry.pendingFor("test-agent").isEmpty());
    }

    private final @NonNull UserQuestionRegistry registry = new UserQuestionRegistry();
    private final @NonNull AskUserTool tool =
            new AskUserTool(new UserInteractionCapabilityImpl(registry));

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 10})
    void acceptsBatchesAndReturnsAnswersByStableId(int count) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var result =
                    executor.submit(
                            () ->
                                    CapabilityTestCalls.execute(
                                            tool, new AskUserTool.Args(questions(count))));
            String callId = awaitPending();
            Map<String, String> answers = new LinkedHashMap<>();
            // Deliberately answer in reverse order: matching is by id, not array position.
            for (int i = count - 1; i >= 0; i--) answers.put("question_" + i, "Custom answer " + i);
            assertTrue(registry.answer("test-agent", callId, answers));
            var json = new ObjectMapper().readTree(result.get(2, TimeUnit.SECONDS));
            assertEquals(count, json.path("answers").size());
            answers.forEach(
                    (id, answer) -> assertEquals(answer, json.path("answers").path(id).asText()));
            assertTrue(registry.pendingFor("test-agent").isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void rejectsOutOfRangeBatchesBeforeWaiting(int count) {
        assertInvalid(questions(count));
    }

    @Test
    void rejectsInvalidQuestionsBeforeRegistering() {
        var valid = question("question_0");
        assertInvalid(List.of(valid, valid));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                " ", valid.id(), valid.question(), valid.options())));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                "x".repeat(13), valid.id(), valid.question(), valid.options())));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(), "Bad-ID", valid.question(), valid.options())));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(), valid.id(), " ", valid.options())));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(), valid.id(), "x".repeat(301), valid.options())));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(),
                                valid.id(),
                                valid.question(),
                                List.of(valid.options().getFirst()))));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(),
                                valid.id(),
                                valid.question(),
                                List.of(
                                        new AskUserTool.Option("Other", "Reserved choice"),
                                        valid.options().getLast()))));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(),
                                valid.id(),
                                valid.question(),
                                List.of(
                                        valid.options().getFirst(),
                                        new AskUserTool.Option(" second ", "Duplicate"),
                                        new AskUserTool.Option("SECOND", "Duplicate")))));
        assertInvalid(
                List.of(
                        new AskUserTool.Question(
                                valid.header(),
                                valid.id(),
                                valid.question(),
                                List.of(
                                        valid.options().getFirst(),
                                        new AskUserTool.Option("Second", " ")))));
    }

    @Test
    void userCancellationReturnsCancelledAndRemovesPendingBatch() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var result =
                    executor.submit(
                            () ->
                                    CapabilityTestCalls.execute(
                                            tool, new AskUserTool.Args(questions(10))));
            assertTrue(registry.cancel("test-agent", awaitPending()));
            var failure =
                    assertThrows(
                            ToolDocs.nonNullClass(ExecutionException.class),
                            () -> result.get(2, TimeUnit.SECONDS));
            var error =
                    assertInstanceOf(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            Nullness.requireNonNull(failure.getCause()));
            assertEquals(ToolResultStatus.CANCELLED, error.status());
            assertEquals(ToolErrorCode.USER_CANCELLED, error.errorCode());
            assertTrue(registry.pendingFor("test-agent").isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedExecutionStopsWaitingAndRemovesOnlyItsBatch() throws Exception {
        var other = registry.register("test-agent", "other-call", questions(1));
        CompletableFuture<Throwable> completed = new CompletableFuture<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        CapabilityTestCalls.execute(
                                                tool, new AskUserTool.Args(questions(1)));
                                        completed.complete(
                                                new AssertionError("Expected interruption"));
                                    } catch (Exception error) {
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                        completed.complete(error);
                                    }
                                });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.pendingFor("test-agent").size() != 2 && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertEquals(2, registry.pendingFor("test-agent").size());
            worker.interrupt();
            var error =
                    assertInstanceOf(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            completed.get(2, TimeUnit.SECONDS));
            assertEquals(ToolResultStatus.CANCELLED, error.status());
            assertEquals(ToolErrorCode.TOOL_INTERRUPTED, error.errorCode());
            assertTrue(interrupted.get());
            assertEquals(1, registry.pendingFor("test-agent").size());
            assertFalse(other.isDone());
        } finally {
            worker.interrupt();
            other.cancel(false);
        }
    }

    @Test
    void overlongRecommendationIdentifiesTheQuestionAndIncludesSuffixInLimit() {
        var question =
                new AskUserTool.Question(
                        "Project",
                        "project",
                        "Create the project?",
                        List.of(
                                new AskUserTool.Option(
                                        "😀".repeat(107) + " (Recommended)", "Create files."),
                                new AskUserTool.Option("Show code", "Show the code first.")));
        var error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool, new AskUserTool.Args(List.of(question))));
        assertEquals(ToolErrorCode.INVALID_QUESTIONS, error.errorCode());
        assertTrue(
                String.valueOf(error.getMessage())
                        .contains("Question 'project', option 1: label has 121"));
        assertTrue(String.valueOf(error.getMessage()).contains("no questions were sent"));
        assertTrue(registry.pendingFor("test-agent").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Create Xcode project skeleton (Recommended)", "unicode-boundary"})
    void acceptsLongLabelsAndPreservesSelectedAnswer(@NonNull String example) throws Exception {
        String label =
                example.equals("unicode-boundary") ? "😀".repeat(106) + " (Recommended)" : example;
        var question =
                new AskUserTool.Question(
                        "Project",
                        "project",
                        "Create the project?",
                        List.of(
                                new AskUserTool.Option(label, "Create files."),
                                new AskUserTool.Option("Show code", "Show code first.")));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var result =
                    executor.submit(
                            () ->
                                    CapabilityTestCalls.execute(
                                            tool, new AskUserTool.Args(List.of(question))));
            assertTrue(registry.answer("test-agent", awaitPending(), Map.of("project", label)));
            assertEquals(
                    label,
                    new ObjectMapper()
                            .readTree(result.get(2, TimeUnit.SECONDS))
                            .path("answers")
                            .path("project")
                            .asText());
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertInvalid(@NonNull List<AskUserTool.Question> questions) {
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> {
                    var error =
                            assertThrows(
                                    ToolDocs.nonNullClass(ToolExecutionException.class),
                                    () ->
                                            CapabilityTestCalls.execute(
                                                    tool, new AskUserTool.Args(questions)));
                    assertEquals(ToolErrorCode.INVALID_QUESTIONS, error.errorCode());
                    assertTrue(registry.pendingFor("test-agent").isEmpty());
                });
    }

    private @NonNull String awaitPending() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var pending = registry.pendingFor("test-agent");
            if (!pending.isEmpty()) return String.valueOf(pending.getFirst().get("callId"));
            Thread.sleep(5);
        }
        throw new AssertionError("Tool did not register its question batch");
    }

    private static @NonNull List<AskUserTool.Question> questions(int count) {
        List<AskUserTool.Question> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(question("question_" + i));
        return result;
    }

    private static AskUserTool.@NonNull Question question(@NonNull String id) {
        return new AskUserTool.Question(
                "Choice",
                id,
                "Which option?",
                List.of(
                        new AskUserTool.Option("First (Recommended)", "The default choice."),
                        new AskUserTool.Option("Second", "The alternative.")));
    }
}
