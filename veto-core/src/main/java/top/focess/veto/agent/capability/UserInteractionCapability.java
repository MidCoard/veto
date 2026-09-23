package top.focess.veto.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.builtin.AskUserTool.Question;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;
import top.focess.veto.api.agent.capability.Capability;

public sealed interface UserInteractionCapability extends Capability
        permits UserInteractionCapabilityImpl {
    UserQuestionRegistry.@NonNull AnswerBatch ask(@NonNull List<@NonNull Question> questions)
            throws InterruptedException;
}
