package top.focess.veto.sandbox;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The result of a {@code run_command} chain.
 *
 * @param exitCode the chain's overall exit code
 * @param stdout the combined stdout (or the final stage's for PIPE)
 * @param stderr the combined stderr
 * @param perCommand exit codes per command, in order
 */
public record CommandResult(
        int exitCode,
        @NonNull String stdout,
        @NonNull String stderr,
        @NonNull List<Integer> perCommand) {

    public boolean success() {
        return exitCode == 0;
    }
}
