package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.loop.ResponseEnforcer;
import top.focess.veto.group.GroupTools.CreateGroup.Args;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.util.Nullness;

class DelegationExamplesTest {
    @Test
    void examplesValidateRealDelegationArgumentsAndDirectAnswers() throws Exception {
        String prompt = new SystemPromptResolver().delegationPrompt();
        assertFalse(
                Pattern.compile("(?i)\\b(leader|mates?)\\b").matcher(prompt).find(),
                "delegation guidance must not introduce later roles");
        var matcher = Pattern.compile("```json\\s*([\\s\\S]*?)```").matcher(prompt);
        ObjectMapper mapper = new ObjectMapper();
        List<String> briefs = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        int examples = 0;
        while (matcher.find()) {
            String json = Nullness.requireNonNull(matcher.group(1));
            var response = mapper.readValue(json, ToolDocs.nonNullClass(VetoResponse.class));
            ResponseEnforcer.enforce(response, false, Set.of("create_group"));
            assertNull(response.guide(), "delegation examples do not require guided execution");
            var calls = response.calls();
            if (calls != null) {
                assertEquals(1, calls.size());
                var call = calls.getFirst();
                assertEquals("create_group", call.toolName());
                var arguments = mapper.valueToTree(call.args());
                NativeToolArgumentValidator.validate(
                        "create_group", arguments, ToolDocs.nonNullClass(Args.class));
                var args = mapper.treeToValue(arguments, ToolDocs.nonNullClass(Args.class));
                assertFalse(args.task().isBlank());
                assertEquals(
                        Set.of("task"),
                        call.args().keySet(),
                        "the actual tool takes only a task brief");
                briefs.add(args.task().toLowerCase(Locale.ROOT));
            } else {
                String message = response.message();
                if (message == null) throw new AssertionError("direct answer missing");
                answers.add(message);
            }
            examples++;
        }
        assertEquals(4, examples);
        assertEquals(2, briefs.size());
        assertEquals(2, answers.size());
        assertTrue(briefs.get(0).contains("backend") && briefs.get(0).contains("frontend"));
        assertTrue(briefs.get(0).contains("outputs") && briefs.get(0).contains("regression tests"));
        assertTrue(briefs.get(0).contains("preserve unrelated edits"));
        assertTrue(
                briefs.get(1).contains("/api/search-presets")
                        && briefs.get(1).contains("persistence"));
        assertTrue(briefs.get(1).contains("outputs") && briefs.get(1).contains("verify"));
        assertTrue(briefs.get(1).contains("preserve existing search behavior"));
        assertTrue(answers.get(0).contains("0") && answers.get(0).contains("length"));
        assertTrue(
                answers.get(1).endsWith("?"),
                "unspecified work requires a question, not delegation");
    }
}
