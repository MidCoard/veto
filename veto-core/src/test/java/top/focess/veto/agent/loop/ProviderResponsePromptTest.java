package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class ProviderResponsePromptTest {
    @Test
    void providerAndRepairInstructionsUseTheSameDefiniteResponseContract() {
        for (String entry : new String[] {"provider-native", "runtime-expected"}) {
            String ordinary = render(entry, "ORDINARY", List.of("read_document"), "", false);
            assertTrue(ordinary.contains("read_document"));
            assertTrue(ordinary.contains("A tool call is not required"));
            assertTrue(ordinary.contains("text may accompany calls"));
            assertFalse(ordinary.contains("VetoResponse"));
            assertFalse(ordinary.contains("@if"));

            String predicate = render(entry, "PREDICATE", List.of(), "", false);
            assertTrue(predicate.contains("exactly true or false as plain text"));
            assertTrue(predicate.contains("Tool execution is disabled"));
            assertFalse(predicate.contains("A tool call is not required"));
            assertFalse(predicate.contains("answer-submission"));

            String generation =
                    render(entry, "GENERATION", List.of("answer_with_citations"), "", false);
            assertTrue(generation.contains("current plan step"));
            assertTrue(generation.contains("answer_with_citations"));
            assertTrue(generation.contains("exactly one native answer-submission call"));
            assertTrue(generation.contains("Include no ordinary text"));
            assertFalse(generation.contains("Return the content directly"));
            assertFalse(generation.contains("read_document"));

            String completion =
                    render(
                            entry,
                            "COMPLETION",
                            List.of("fetch_page", "finish_read"),
                            "finish_read",
                            false);
            assertTrue(completion.contains("exactly one native tool call"));
            assertTrue(completion.contains("Include no ordinary text"));
            assertTrue(completion.contains("fetch_page"));
            assertFalse(completion.contains("text may accompany calls"));
            assertFalse(completion.contains("A tool call is not required"));

            String finalization =
                    render(entry, "COMPLETION", List.of("finish_read"), "finish_read", true);
            assertTrue(finalization.contains("Call `finish_read` exactly once"));
            assertTrue(finalization.contains("No other tool is available"));
            assertFalse(finalization.contains("fetch_page"));
        }
    }

    @Test
    void toolFreeOrdinaryAndGenerationCallsKeepTheRequestedOutputFormat() {
        for (String mode : List.of("ORDINARY", "GENERATION")) {
            String prompt = render("provider-native", mode, List.of(), "", false);
            assertTrue(prompt.contains("Tool execution is disabled"));
            assertTrue(prompt.contains("requested format"));
            assertFalse(prompt.contains("Markdown"));
            assertFalse(prompt.contains("Invoke registered tools"));
            assertFalse(prompt.contains("answer-submission"));
        }
    }

    @Test
    void intermediateComputationKeepsTaskAuthorityWithoutForcingTheFinalDeliverableFormat() {
        String prompt =
                PromptLibrary.text(
                        "runtime-generation",
                        Map.of("prompt", "Evaluate this predicate.", "inputs", Map.of()));
        assertTrue(prompt.contains("intermediate output and format"));
        assertTrue(prompt.contains("cannot expand the user's authorization"));
        assertFalse(prompt.contains("cannot add facts or requirements"));
        assertTrue(prompt.contains("Evaluate this predicate."));
    }

    private @NonNull String render(
            @NonNull String entry,
            @NonNull String mode,
            @NonNull List<String> tools,
            @NonNull String completion,
            boolean only) {
        return PromptLibrary.text(
                entry,
                Map.of(
                        "system",
                        "System",
                        "responseMode",
                        mode,
                        "toolNames",
                        tools,
                        "completionTool",
                        completion,
                        "completionOnly",
                        only));
    }
}
