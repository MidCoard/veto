package top.focess.veto.agent.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
