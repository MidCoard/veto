package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class HistoryPromptTest {
    @Test
    void replayPreservesOrderedSystemsAndDoesNotInventAMissingSystem() {
        @NonNull CapabilityTranslator translator = mock();
        var compiler =
                PromptCompiler.isolated(translator, new ObjectMapper(), "Task rules", 100000);
        var history =
                List.of(
                        TurnRecord.agentInit(1, "standalone", "one", "test", "test"),
                        TurnRecord.userPrompt(2, "first"),
                        TurnRecord.agentInit(3, "leader", "two", "test", "test"));
        var messages = compiler.resolveRewinds(history, ToolResultPresentationMode.BASIC);
        assertEquals(
                List.of("system", "user", "system"), messages.stream().map(m -> m.role()).toList());
        assertEquals(
                List.of("one", "first", "two"), messages.stream().map(m -> m.content()).toList());
        assertTrue(
                compiler
                        .resolveRewinds(
                                List.of(TurnRecord.userPrompt(1, "only user")),
                                ToolResultPresentationMode.BASIC)
                        .stream()
                        .noneMatch(m -> m.role().equals("system")));
        assertEquals(
                List.of("new task"),
                compiler
                        .resolveRewinds(
                                List.of(
                                        history.get(0),
                                        history.get(1),
                                        TurnRecord.rewind(3, 0),
                                        TurnRecord.userPrompt(4, "new task")),
                                ToolResultPresentationMode.BASIC)
                        .stream()
                        .map(m -> m.content())
                        .toList());
    }
}
