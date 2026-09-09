package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class WebFetchExecutorResultTest {
    private static final WebFetchExecutor.@NonNull Execution EXECUTION =
            new WebFetchExecutor.Execution("child-1", "configured-reader", 100, 3, 1000, 100);

    @Test
    void completeRequiresReadEvidenceAndReturnsHostSourceQuotes() {
        WebReadDocument document = document("The timeout is 30 seconds.", false);
        FinishReadTool.Args finish =
                new FinishReadTool.Args("complete", "30 seconds", List.of("s1"), List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> WebFetchExecutor.finish(finish, document, EXECUTION));
        document.read(List.of("s1"));
        document.recordInspection(List.of("s1"));
        WebFetchExecutor.Result result = WebFetchExecutor.finish(finish, document, EXECUTION);

        assertEquals("complete", result.outcome());
        assertEquals("The timeout is 30 seconds.", result.evidence().getFirst().quote());
        assertEquals(EXECUTION, result.execution());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WebFetchExecutor.finish(
                                new FinishReadTool.Args(
                                        "complete", "Unsubstantiated answer", List.of(), List.of()),
                                document,
                                EXECUTION));
    }

    @Test
    void notFoundRequiresCompleteInspectionAndBecomesPartialOtherwise() {
        WebReadDocument document =
                document("No timeout is specified. " + "Other settings. ".repeat(100), false);
        FinishReadTool.Args finish =
                new FinishReadTool.Args("not_found", "No timeout found.", List.of(), List.of());
        document.read(List.of("s1"));
        document.recordInspection(List.of("s1"));
        WebFetchExecutor.Result incomplete = WebFetchExecutor.finish(finish, document, EXECUTION);
        assertEquals("partial", incomplete.outcome());
        assertTrue(
                incomplete.limitations().stream()
                        .anyMatch(value -> value.contains("coverage is incomplete")));

        document.read(document.outline().stream().map(WebReadDocument.Entry::id).toList());
        document.recordInspection(
                document.outline().stream().map(WebReadDocument.Entry::id).toList());
        WebFetchExecutor.Result complete = WebFetchExecutor.finish(finish, document, EXECUTION);
        assertEquals("not_found", complete.outcome());
        assertTrue(complete.evidence().isEmpty());
        assertTrue(complete.limitations().isEmpty());
    }

    @Test
    void upstreamTruncationDowngradesAnOtherwiseSupportedAnswer() {
        WebReadDocument document = document("Timeout is 30 seconds.", true);
        document.read(List.of("s1"));
        document.recordInspection(List.of("s1"));
        WebFetchExecutor.Result result =
                WebFetchExecutor.finish(
                        new FinishReadTool.Args(
                                "complete",
                                "30 seconds",
                                List.of("s1"),
                                List.of("Only version 3 was checked.")),
                        document,
                        EXECUTION);

        assertEquals("partial", result.outcome());
        assertEquals(1, result.evidence().size());
        assertEquals("Only version 3 was checked.", result.limitations().getFirst());
        assertTrue(result.limitations().stream().anyMatch(value -> value.contains("truncated")));
    }

    @Test
    void partialRetainsTheReadersCoverageLimitationsAndDeduplicatesEvidence() {
        WebReadDocument document = document("Version 3 uses seconds.", false);
        document.read(List.of("s1"));
        document.recordInspection(List.of("s1"));
        WebFetchExecutor.Result result =
                WebFetchExecutor.finish(
                        new FinishReadTool.Args(
                                "partial",
                                "v3 uses seconds.",
                                List.of("s1", "s1"),
                                List.of("Other versions have not been checked.")),
                        document,
                        EXECUTION);

        assertEquals("partial", result.outcome());
        assertEquals(1, result.evidence().size());
        assertEquals(List.of("Other versions have not been checked."), result.limitations());
        assertFalse(result.answer().isBlank());
    }

    @Test
    void rejectsUnknownOutcomesAndUnboundedOutput() {
        WebReadDocument document = document("Source", false);
        document.read(List.of("s1"));
        document.recordInspection(List.of("s1"));
        for (FinishReadTool.Args invalid :
                List.of(
                        new FinishReadTool.Args("success", "Answer", List.of("s1"), List.of()),
                        new FinishReadTool.Args("partial", " ", List.of("s1"), List.of()),
                        new FinishReadTool.Args(
                                "complete", "x".repeat(4001), List.of("s1"), List.of()),
                        new FinishReadTool.Args(
                                "partial", "Answer", List.of("s1"), List.of("x".repeat(501))))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WebFetchExecutor.finish(invalid, document, EXECUTION));
        }
    }

    private static @NonNull WebReadDocument document(@NonNull String content, boolean truncated) {
        return new WebReadDocument(
                new FetchedPage(
                        URI.create("https://example.com/docs"),
                        200,
                        "text/plain",
                        content,
                        truncated,
                        500000));
    }
}
