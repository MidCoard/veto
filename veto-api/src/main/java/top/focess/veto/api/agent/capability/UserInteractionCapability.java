package top.focess.veto.api.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.interaction.AnswerBatch;
import top.focess.veto.api.interaction.Question;

public interface UserInteractionCapability extends Capability {
    @NonNull AnswerBatch ask(@NonNull List<@NonNull Question> questions)
            throws InterruptedException;
}
