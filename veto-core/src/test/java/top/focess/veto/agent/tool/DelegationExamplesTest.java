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
    void exampleUsesNativeArgumentsAndGuidancePreservesDelegationBoundaries() throws Exception {
        String prompt = PromptLibrary.text("delegation-system-prompt");
        assertFalse(
                Pattern.compile("(?i)\\b(leader|mates?)\\b").matcher(prompt).find(),
                "delegation guidance must not introduce later roles");
        var matcher = Pattern.compile("```json\\s*([\\s\\S]*?)```").matcher(prompt);
        ObjectMapper mapper = new ObjectMapper();
        List<String> briefs = new ArrayList<>();
        int examples = 0;
        while (matcher.find()) {
            String json = Nullness.requireNonNull(matcher.group(1)).strip();
            examples++;
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
        assertEquals(1, examples);
        assertEquals(1, briefs.size());
        assertTrue(prompt.contains("honor the requested number of distinct collaborators"));
        assertTrue(prompt.contains("Work directly on small or tightly coupled tasks"));
        assertTrue(prompt.contains("Resolve a missing objective before assigning work"));
        assertTrue(briefs.get(0).contains("backend") && briefs.get(0).contains("frontend"));
        assertTrue(briefs.get(0).contains("outputs") && briefs.get(0).contains("regression tests"));
        assertTrue(briefs.get(0).contains("preserve unrelated edits"));
    }
}
