package top.focess.veto.api.process;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;

/** Executes immutable process intent already admitted by the current Gateway permit. */
public interface ProcessHost {
    @NonNull CommandResult runApproved();

    @NonNull Running startApproved();

    @NonNull Duration maxRuntime();

    /** Captures the exact approved bytes/EOF intent and process instance for one delivery. */
    @NonNull InputWrite prepareInput(@NonNull Running process);

    interface Running extends AutoCloseable {
        @NonNull UUID id();

        PluginHost.@NonNull Invocation invocation();

        @NonNull Command command();

        @NonNull String cwd();

        boolean networkAllowed();

        @NonNull Duration maxRuntime();

        long pid();

        /**
         * Read-only merged stdout/stderr. Every read requires the original plugin/session
         * admission.
         */
        @NonNull InputStream output();

        /**
         * Termination facts and waits remain available during cleanup after admission is revoked.
         */
        boolean isAlive();

        int waitFor() throws InterruptedException;

        boolean awaitExit(@NonNull Duration timeout) throws InterruptedException;

        int exitValue();

        boolean timedOut();

        @Override
        void close();
    }

    interface InputWrite {
        int byteCount();

        boolean closeStdin();

        /** Consumed once, including a failed delivery. The host revalidates scope at delivery. */
        void write() throws IOException;
    }
}
