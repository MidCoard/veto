package top.focess.veto.api.agent.tool;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.ProcessHost;

/**
 * A typed requested effect plus plugin-supplied screening facts. Facts are advisory; only the host
 * can admit an intent.
 *
 * @param intent normalized effect requested by the tool
 * @param facts structured facts available to host screening
 */
public record ToolPreparation(@NonNull Intent intent, JsonValue.@NonNull ObjectValue facts) {
    /** Closed set of effects supported by the current preparation boundary. */
    public sealed interface Intent permits ProcessIntent, InputIntent {}

    /**
     * Immutable request to launch a command chain.
     *
     * @param commands one to 64 discrete commands
     * @param mode how adjacent commands are connected
     * @param network whether the process requests network access
     * @param timeout requested non-negative runtime limit
     */
    public record ProcessIntent(
            @NonNull List<Command> commands,
            @NonNull ChainMode mode,
            boolean network,
            @NonNull Duration timeout)
            implements Intent {
        /** Validates bounds and deeply copies the command list. */
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

    /**
     * Immutable request to deliver bytes to an existing managed process.
     *
     * @param process target managed process
     * @param bytes bytes captured for delivery; defensively copied and limited to 1 MiB
     * @param closeStdin whether delivery should close the target's standard input
     */
    public record InputIntent(
            ProcessHost.@NonNull Running process, byte @NonNull [] bytes, boolean closeStdin)
            implements Intent {
        /** Validates the payload bound and captures a defensive byte copy. */
        public InputIntent {
            bytes = bytes.clone();
            if (bytes.length > 1_048_576)
                throw new IllegalArgumentException("Input exceeds host limit");
        }

        /**
         * Reads the captured input without exposing the stored array.
         *
         * @return a defensive copy of the captured bytes
         */
        @Override
        public byte @NonNull [] bytes() {
            return bytes.clone();
        }
    }
}
