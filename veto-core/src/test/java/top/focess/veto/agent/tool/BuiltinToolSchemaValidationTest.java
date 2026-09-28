package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.builtin.questions.Option;
import top.focess.veto.builtin.questions.Question;
import top.focess.veto.builtin.response.AnswerWithCitationsTool;
import top.focess.veto.builtin.tools.AskUserTool;

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
