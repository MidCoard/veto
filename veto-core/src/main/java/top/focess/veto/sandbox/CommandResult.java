package top.focess.veto.sandbox;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The result of a {@code run_command} chain..
 *
 * @param exitCode the chain's overall exit code (last command's for STOP_ON_FAILURE/PIPE; 0 if all
 * @param stdout the combined stdout (or the final stage's for PIPE)
 * @param stderr the combined stderr
 * @param perCommand exit codes per command, in order
 */
public record CommandResult(
        int exitCode,
        @NonNull String stdout,
        @Nullable String stderr,
        @NonNull List<Integer> perCommand) {

    public boolean success() {
        return exitCode == 0;
    }
}
