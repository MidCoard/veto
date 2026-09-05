package top.focess.veto.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.builtin.AskUserTool.Question;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;

@Component
public final class UserInteractionCapabilityImpl implements UserInteractionCapability {
    private final @NonNull UserQuestionRegistry registry;

    public UserInteractionCapabilityImpl(@NonNull UserQuestionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public UserQuestionRegistry.@NonNull AnswerBatch ask(
            @NonNull List<@NonNull Question> questions) {
        var context = CapabilityAccess.require(ToolCapability.USER_INTERACTION, "ask_user");
        return registry.register(
                        context.agentId(),
                        context.executionPermit().callId(),
                        List.copyOf(questions))
                .join();
    }
}
