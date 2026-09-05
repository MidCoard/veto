package top.focess.veto.agent.capability;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolResultStatus;
import top.focess.veto.agent.tool.builtin.AskUserTool;
import top.focess.veto.agent.tool.builtin.AskUserTool.Option;
import top.focess.veto.agent.tool.builtin.AskUserTool.Question;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;

@Component
public final class UserInteractionCapabilityImpl implements UserInteractionCapability {
    private final @NonNull UserQuestionRegistry registry;

    public UserInteractionCapabilityImpl(@NonNull UserQuestionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public @NonNull String ask(AskUserTool.@NonNull Args args) throws Exception {
        var context = CapabilityAccess.require(ToolCapability.USER_INTERACTION, "ask_user", args);

        validate(args.questions());

        String callId = context.executionPermit().callId();
        UserQuestionRegistry.AnswerBatch answer =
                registry.register(context.agentId(), callId, List.copyOf(args.questions())).join();
        if (answer.cancelled()) {
            throw new ToolExecutionException(
                    ToolResultStatus.CANCELLED,
                    ToolResultFormat.PLAINTEXT,
                    "USER_CANCELLED",
                    "The user cancelled the question.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answers", answer.answers());
        return ToolJson.object(result);
    }

    private static void validate(@NonNull List<@NonNull Question> questions) {
        if (questions.isEmpty() || questions.size() > 3) {
            ToolErrors.failure("INVALID_QUESTIONS", "ask_user requires between 1 and 3 questions.");
        }
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (question.header().isBlank() || length(question.header()) > 12) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS",
                        "Each question header must contain 1 to 12 characters.");
            }
            if (question.id().isBlank()
                    || !question.id().matches("[a-z][a-z0-9_]*")
                    || !ids.add(question.id())) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS", "Question ids must be unique snake_case identifiers.");
            }
            if (question.question().isBlank() || length(question.question()) > 300) {
                ToolErrors.failure(
                        "INVALID_QUESTIONS",
                        "Each question prompt must contain 1 to 300 characters.");
            }
            if (question.options().size() < 2 || question.options().size() > 3) {
                ToolErrors.failure("INVALID_QUESTIONS", "Each question requires 2 or 3 options.");
            }
            Set<String> labels = new HashSet<>();
            for (int index = 0; index < question.options().size(); index++) {
                Option option = question.options().get(index);
                String normalizedLabel = option.label().strip().toLowerCase(Locale.ROOT);
                if (option.label().isBlank()
                        || length(option.label()) > 40
                        || "other".equals(normalizedLabel)
                        || option.description().isBlank()
                        || length(option.description()) > 200
                        || !labels.add(normalizedLabel)) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Option labels must be distinct, at most 40 characters, and not"
                                    + " `Other`; descriptions must contain 1 to 200 characters.");
                }
                boolean recommended = option.label().endsWith("(Recommended)");
                if ((index == 0) != recommended) {
                    ToolErrors.failure(
                            "INVALID_QUESTIONS",
                            "Exactly the first option must end with `(Recommended)`.");
                }
            }
        }
    }

    private static int length(@NonNull String value) {
        return value.codePointCount(0, value.length());
    }
}
