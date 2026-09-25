package top.focess.veto.api.process;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;

/**
 * Executes immutable process intent already admitted by the current Gateway permit.
 *
 * <p>Instances are scoped to the current invocation. Methods that start or interact with a process
 * revalidate that scope; possessing a retained reference is not continuing authority.
 */
public interface ProcessHost {
    /**
     * Runs the admitted command chain to completion.
     *
     * @return captured process result
     */
    @NonNull CommandResult runApproved();

    /**
     * Starts the admitted command chain.
     *
     * @return managed live-process handle
     */
    @NonNull Running startApproved();

    /**
     * Returns the host-enforced maximum lifetime for the admitted process.
     *
     * @return maximum process lifetime
     */
    @NonNull Duration maxRuntime();

    /**
     * Captures the exact approved bytes/EOF intent for one delivery.
     *
     * @param process managed process receiving input
     * @return single-use prepared stdin delivery
     */
    @NonNull InputWrite prepareInput(@NonNull Running process);

    /** A host-managed process started by {@link #startApproved()}. */
    interface Running extends AutoCloseable {
        /**
         * Returns the host process record identifier, distinct from the operating-system PID.
         *
         * @return host process record identifier
         */
        @NonNull UUID id();

        /**
         * Returns the invocation whose admission governs process interaction.
         *
         * @return owning invocation
         */
        PluginHost.@NonNull Invocation invocation();

        /**
         * Returns the immutable command used to start this process.
         *
         * @return launched command
         */
        @NonNull Command command();

        /**
         * Returns the resolved working-directory display value.
         *
         * @return display path of the working directory
         */
        @NonNull String cwd();

        /**
         * Returns whether this admitted process may use network access.
         *
         * @return whether network access was approved
         */
        boolean networkAllowed();

        /**
         * Returns the enforced runtime ceiling.
         *
         * @return maximum runtime
         */
        @NonNull Duration maxRuntime();

        /**
         * Returns the operating-system process identifier.
         *
         * @return operating-system PID
         */
        long pid();

        /**
         * Read-only merged stdout/stderr. Every read requires the original plugin/session
         * admission.
         *
         * @return admitted merged output stream
         */
        @NonNull InputStream output();

        /**
         * Reports liveness even during cleanup after admission is revoked.
         *
         * @return whether the process is still running
         */
        boolean isAlive();

        /**
         * Waits for termination.
         *
         * @return process exit code
         * @throws InterruptedException if the waiting thread is interrupted
         */
        int waitFor() throws InterruptedException;

        /**
         * Waits up to {@code timeout} for termination.
         *
         * @param timeout maximum wait duration
         * @return whether the process exited before the deadline
         * @throws InterruptedException if the waiting thread is interrupted
         */
        boolean awaitExit(@NonNull Duration timeout) throws InterruptedException;

        /**
         * Returns the exit code of an already terminated process.
         *
         * @return process exit code
         * @throws IllegalThreadStateException if the process is still alive
         */
        int exitValue();

        /**
         * Returns whether the host terminated the process for exceeding its runtime.
         *
         * @return whether the runtime ceiling was exceeded
         */
        boolean timedOut();

        /** Releases the handle and requests cleanup of a still-running managed process. */
        @Override
        void close();
    }

    /** A single-use, bounded stdin delivery prepared for one managed process. */
    interface InputWrite {
        /**
         * Returns the exact number of bytes captured for delivery.
         *
         * @return captured byte count
         */
        int byteCount();

        /**
         * Returns whether successful delivery also closes process stdin.
         *
         * @return whether EOF follows this delivery
         */
        boolean closeStdin();

        /**
         * Delivers input once, including a failed delivery. The host revalidates scope at delivery.
         *
         * @throws IOException if delivery fails
         */
        void write() throws IOException;
    }
}
