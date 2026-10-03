package top.focess.veto.agent;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.contract.AgentInbox;

/** Immutable handoffs to the existing Agent execution queue. */
sealed interface RunnerCommand
        permits QueuedRequest,
                RunnerCommand.Inputs,
                RunnerCommand.History,
                RunnerCommand.WorkSource,
                RunnerCommand.Wake,
                RunnerCommand.Policy {
    record Inputs(@NonNull LlmBinding binding, @NonNull Locale locale) implements RunnerCommand {}

    record History(@NonNull List<TurnRecord> turns) implements RunnerCommand {
        public History {
            turns = List.copyOf(turns);
        }
    }

    record Wake() implements RunnerCommand {}

    record Policy(@NonNull AgentExecutionPolicy policy) implements RunnerCommand {}

    record WorkSource(@NonNull AgentInbox inbox) implements RunnerCommand {}
}
