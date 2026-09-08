package top.focess.veto.agent.capability;

import java.util.List;
import java.util.concurrent.ExecutionException;
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
    public UserQuestionRegistry.@NonNull AnswerBatch ask(@NonNull List<@NonNull Question> questions)
            throws InterruptedException {
        var context = CapabilityAccess.require(ToolCapability.USER_INTERACTION, "ask_user");
        var pending =
                registry.register(context.agentId(), context.executionPermit().callId(), questions);
        try {
            return pending.get();

        } catch (ExecutionException e) {
            throw new IllegalStateException("Question waiting failed", e.getCause());
        } finally {
            pending.cancel(false);
        }
    }
}
