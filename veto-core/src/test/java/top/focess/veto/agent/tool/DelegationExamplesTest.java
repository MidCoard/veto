package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.loop.PromptLibrary;
import top.focess.veto.group.GroupTools.CreateGroup.Args;
import top.focess.veto.util.Nullness;

class DelegationExamplesTest {
    @Test
    void examplesValidateRealDelegationArgumentsAndDirectAnswers() throws Exception {
        String prompt = PromptLibrary.text("delegation-system-prompt");
        assertFalse(
                Pattern.compile("(?i)\\b(leader|mates?)\\b").matcher(prompt).find(),
                "delegation guidance must not introduce later roles");
        var matcher = Pattern.compile("```(json|text)\\s*([\\s\\S]*?)```").matcher(prompt);
        ObjectMapper mapper = new ObjectMapper();
        List<String> briefs = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        int examples = 0;
        while (matcher.find()) {
            String example = Nullness.requireNonNull(matcher.group(2)).strip();
            examples++;
            if ("text".equals(matcher.group(1))) {
                assertFalse(
                        example.startsWith("{"), "direct replies must not use a response envelope");
                answers.add(example);
                continue;
            }
            String json = example;
            var arguments = mapper.readTree(json);
            assertFalse(arguments.has("calls"));
            assertTrue(arguments.has("task"), "JSON examples must be native tool arguments");
            NativeToolArgumentValidator.validate(
                    "create_group", arguments, ToolDocs.nonNullClass(Args.class));
            var args = mapper.treeToValue(arguments, ToolDocs.nonNullClass(Args.class));
            assertFalse(args.task().isBlank());
            assertEquals(1, arguments.size());
            briefs.add(args.task().toLowerCase(Locale.ROOT));
        }
        assertEquals(5, examples);
        assertEquals(3, briefs.size());
        assertTrue(briefs.get(2).contains("two distinct collaborators"));
        assertTrue(briefs.get(2).contains("explicit request"));
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
