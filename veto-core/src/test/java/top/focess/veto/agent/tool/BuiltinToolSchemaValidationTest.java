package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.builtin.questions.Option;
import top.focess.veto.builtin.questions.Question;
import top.focess.veto.builtin.response.AnswerWithCitationsTool;
import top.focess.veto.builtin.tools.AskUserTool;
import top.focess.veto.builtin.tools.ReadGitHubRepositoryTool;
import top.focess.veto.builtin.tools.RunCommandTool;
import top.focess.veto.builtin.tools.RunTaskTool;
import top.focess.veto.builtin.tools.ViewTaskTool;
import top.focess.veto.builtin.web.FindSectionsTool;
import top.focess.veto.builtin.web.ReadSectionsTool;
import top.focess.veto.builtin.web.WebFetchTool;
import top.focess.veto.builtin.web.WebSearchTool;
import top.focess.veto.builtin.web.model.FinishReadArgs;
import top.focess.veto.builtin.workspace.FindFilesTool;
import top.focess.veto.builtin.workspace.GrepSearchTool;
import top.focess.veto.builtin.workspace.ReplaceFileContentTool;

/**
 * The declarative argument bounds of the builtin tools live only on their record annotations
 * ({@code @ArraySize}, {@code @StringConstraint}); the tools no longer re-check them. These tests
 * pin that the compiled schema, enforced by {@link NativeToolArgumentValidator} before execution,
 * still rejects every bound that used to be duplicated inside the tool bodies.
 */
class BuiltinToolSchemaValidationTest {
    private static final @NonNull Class<AskUserTool.Args> ASK_USER_ARGS = AskUserTool.Args.class;
    private static final @NonNull Class<AnswerWithCitationsTool.Args> ANSWER_WITH_CITATIONS_ARGS =
            AnswerWithCitationsTool.Args.class;

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void processAndWorkspaceStaticBoundsRejectAtHostPreflight() {
        var command = new RunCommandTool.CommandInput("echo", List.of("ok"));
        assertRejected(
                "run_command",
                new RunCommandTool.Args(List.of(), null, false, 0),
                RunCommandTool.Args.class);
        assertRejected(
                "run_command",
                new RunCommandTool.Args(List.of(command), null, false, -1),
                RunCommandTool.Args.class);
        assertRejected(
                "run_task",
                new RunTaskTool.Args(List.of(command, command), false, 0),
                RunTaskTool.Args.class);
        assertRejected(
                "run_task",
                new RunTaskTool.Args(List.of(command), false, -1),
                RunTaskTool.Args.class);
        assertRejected("view_task", new ViewTaskTool.Args(null, true), ViewTaskTool.Args.class);
        assertRejected("view_task", new ViewTaskTool.Args(" ", true), ViewTaskTool.Args.class);
        assertRejected(
                "grep_search",
                new GrepSearchTool.Args("/p", "", null, null),
                GrepSearchTool.Args.class);
        assertRejected(
                "find_files", new FindFilesTool.Args("/p", "\\bad"), FindFilesTool.Args.class);
        assertRejected("find_files", new FindFilesTool.Args("/p", " "), FindFilesTool.Args.class);
        assertRejected(
                "replace_file_content",
                new ReplaceFileContentTool.Args("/p", 0, 1, "x", "y"),
                ReplaceFileContentTool.Args.class);
        assertRejected(
                "replace_file_content",
                new ReplaceFileContentTool.Args("/p", 1, 1, "", "y"),
                ReplaceFileContentTool.Args.class);
    }

    @Test
    void readerPreflightReportsAllOutputViolationsWithoutEchoingContents() {
        var invalid =
                new FinishReadArgs(
                        "invented",
                        "z".repeat(4001),
                        Collections.nCopies(15, "untrusted-id"),
                        Collections.nCopies(9, "q".repeat(501)));
        JsonNode json = mapper.valueToTree(invalid);
        var error =
                assertThrows(
                        ToolExecutionException.class,
                        () ->
                                NativeToolArgumentValidator.validate(
                                        "finish_read", json, FinishReadArgs.class));
        String message = String.valueOf(error.getMessage());
        for (String field : List.of("outcome", "answer", "evidenceIds", "limitations"))
            assertTrue(message.contains(field), message);
        assertFalse(message.contains("untrusted-id"));
        assertFalse(message.contains("zzzz"));
        assertFalse(message.contains("qqqq"));
    }

    @Test
    void publicAndPrivateReaderBoundsUseTheSameHostPreflight() {
        assertRejected(
                "web_search", new WebSearchTool.Args(" x ", null, null), WebSearchTool.Args.class);
        assertRejected(
                "web_search",
                new WebSearchTool.Args("\u2000x\u2000", null, null),
                WebSearchTool.Args.class);
        assertRejected(
                "web_fetch",
                new WebFetchTool.Args("https://example.org", "x".repeat(4001)),
                WebFetchTool.Args.class);
        assertRejected(
                "read_github_repository",
                new ReadGitHubRepositoryTool.Args("ref", "../bad", "repo"),
                ReadGitHubRepositoryTool.Args.class);
        assertRejected(
                "read_github_repository",
                new ReadGitHubRepositoryTool.Args("ref", "owner", ".."),
                ReadGitHubRepositoryTool.Args.class);
        assertRejected(
                "read_sections", new ReadSectionsTool.Args(List.of()), ReadSectionsTool.Args.class);
        assertRejected(
                "read_sections",
                new ReadSectionsTool.Args(Collections.nCopies(9, "s1")),
                ReadSectionsTool.Args.class);
        assertRejected(
                "find_sections", new FindSectionsTool.Args(" "), FindSectionsTool.Args.class);
        assertRejected(
                "find_sections", new FindSectionsTool.Args("\u00a0"), FindSectionsTool.Args.class);
        assertRejected(
                "find_sections",
                new FindSectionsTool.Args("\u00a0\t"),
                FindSectionsTool.Args.class);
        assertRejected(
                "find_sections",
                new FindSectionsTool.Args("x".repeat(201)),
                FindSectionsTool.Args.class);
        for (var invalid :
                List.of(
                        new FinishReadArgs("success", "Answer", List.of(), List.of()),
                        new FinishReadArgs("partial", " ", List.of(), List.of()),
                        new FinishReadArgs("complete", "x".repeat(4001), List.of("s1"), List.of()),
                        new FinishReadArgs(
                                "partial", "Answer", Collections.nCopies(9, "s1"), List.of()),
                        new FinishReadArgs(
                                "partial", "Answer", List.of(), Collections.nCopies(9, "limit")),
                        new FinishReadArgs(
                                "partial", "Answer", List.of(), List.of("x".repeat(501))))) {
            JsonNode json = mapper.valueToTree(invalid);
            var error =
                    assertThrows(
                            ToolExecutionException.class,
                            () ->
                                    NativeToolArgumentValidator.validate(
                                            "finish_read", json, FinishReadArgs.class));
            assertEquals(ToolErrorCode.VALIDATION.INVALID_ARGUMENTS, error.errorCode());
            String message = String.valueOf(error.getMessage());
            assertTrue(message.contains("finish_read"));
            assertFalse(message.contains("xxxx"));
        }
    }

    @Test
    void askUserRejectsQuestionBatchOutsideOneToTen() {
        assertRejected("ask_user", new AskUserTool.Args(List.of()), ASK_USER_ARGS);
        assertRejected("ask_user", new AskUserTool.Args(questions(11)), ASK_USER_ARGS);
    }

    @Test
    void askUserRejectsHeaderPromptAndIdOutsideTheirDeclaredConstraints() {
        assertRejected(
                "ask_user",
                new AskUserTool.Args(List.of(question("x".repeat(13), "scope", "Choose scope"))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(List.of(question("Scope", "scope", "x".repeat(301)))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(List.of(question("Scope", "Bad-ID", "Choose scope"))),
                ASK_USER_ARGS);
    }

    @Test
    void askUserRejectsOptionCountAndFieldLengthsOutsideTheirDeclaredConstraints() {
        assertRejected(
                "ask_user",
                new AskUserTool.Args(
                        List.of(
                                new Question(
                                        "Scope",
                                        "scope",
                                        "Choose scope",
                                        List.of(new Option("Only", "Single choice"))))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(
                        List.of(
                                new Question(
                                        "Scope",
                                        "scope",
                                        "Choose scope",
                                        List.of(
                                                new Option("x".repeat(121), "First choice"),
                                                new Option("Second", "Second choice"))))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(
                        List.of(
                                new Question(
                                        "Scope",
                                        "scope",
                                        "Choose scope",
                                        List.of(
                                                new Option("First", "x".repeat(201)),
                                                new Option("Second", "Second choice"))))),
                ASK_USER_ARGS);
    }

    @Test
    void askUserRejectsNestedBlankReservedAndDuplicateValuesFromAnnotations() {
        var valid = question("Scope", "scope", "Choose scope");
        assertRejected("ask_user", new AskUserTool.Args(List.of(valid, valid)), ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(List.of(question(" ", "scope", "Choose scope"))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(List.of(question("Scope", "scope", " "))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(
                        List.of(
                                new Question(
                                        "Scope",
                                        "scope",
                                        "Choose scope",
                                        List.of(
                                                new Option(" Other ", "Reserved"),
                                                new Option("Second", "Choice"))))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(
                        List.of(
                                new Question(
                                        "Scope",
                                        "scope",
                                        "Choose scope",
                                        List.of(
                                                new Option(" second ", "Choice"),
                                                new Option("SECOND", "Duplicate"))))),
                ASK_USER_ARGS);
        assertRejected(
                "ask_user",
                new AskUserTool.Args(
                        List.of(
                                new Question(
                                        "Scope",
                                        "scope",
                                        "Choose scope",
                                        List.of(
                                                new Option("First", " "),
                                                new Option("Second", "Choice"))))),
                ASK_USER_ARGS);
    }

    @Test
    void answerWithCitationsRejectsAnEmptyCitationArray() {
        assertRejected(
                "answer_with_citations",
                new AnswerWithCitationsTool.Args("Launch Friday.", List.of()),
                ANSWER_WITH_CITATIONS_ARGS);
    }

    private void assertRejected(
            @NonNull String toolName, @NonNull Object args, @NonNull Class<?> argsClass) {
        JsonNode json = mapper.valueToTree(args);
        var error =
                assertThrows(
                        ToolExecutionException.class,
                        () -> NativeToolArgumentValidator.validate(toolName, json, argsClass));
        assertEquals(ToolErrorCode.VALIDATION.INVALID_ARGUMENTS, error.errorCode());
    }

    private static @NonNull List<@NonNull Question> questions(int count) {
        List<Question> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(question("Scope", "scope_" + i, "Choose?"));
        return result;
    }

    private static @NonNull Question question(
            @NonNull String header, @NonNull String id, @NonNull String prompt) {
        return new Question(
                header,
                id,
                prompt,
                List.of(
                        new Option("First", "First choice"),
                        new Option("Second", "Second choice")));
    }
}
