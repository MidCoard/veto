package top.focess.veto.api.agent.tool;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.ProcessHost;

/** Typed requested effects. Plugin facts are advisory; only the host can admit an intent. */
public record ToolPreparation(@NonNull Intent intent, JsonValue.@NonNull ObjectValue facts) {
    public sealed interface Intent permits ProcessIntent, InputIntent {}

    public record ProcessIntent(
            @NonNull List<Command> commands,
            @NonNull ChainMode mode,
            boolean network,
            @NonNull Duration timeout)
            implements Intent {
        public ProcessIntent {
            commands =
                    commands.stream()
                            .map(
                                    command ->
                                            new Command(
                                                    command.executable(),
                                                    List.copyOf(command.args())))
                            .toList();
            if (commands.isEmpty() || commands.size() > 64 || timeout.isNegative())
                throw new IllegalArgumentException("Invalid process intent");
        }
    }

    public record InputIntent(
            ProcessHost.@NonNull Running process, byte @NonNull [] bytes, boolean closeStdin)
            implements Intent {
        public InputIntent {
            bytes = bytes.clone();
            if (bytes.length > 1_048_576)
                throw new IllegalArgumentException("Input exceeds host limit");
        }

        @Override
        public byte @NonNull [] bytes() {
            return bytes.clone();
        }
    }
}
