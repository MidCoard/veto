package top.focess.veto.training;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/** Offline training shared by task owners; tensor computation runs in Python. */
public interface ModelTrainer {
    /** Run synchronously. Interrupt the calling thread to cancel the owned process tree. */
    @NonNull Result train(
            @NonNull TrainingRequest request, @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException;

    /** Continue the same run from a complete checkpoint, restoring optimizer and RNG state. */
    @NonNull Result resume(
            @NonNull TrainingRequest request,
            @NonNull Path checkpoint,
            @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException;

    /**
     * Export an existing merged model, adapter, or optimization-finished checkpoint without
     * training.
     */
    @NonNull Completed export(
            @NonNull TrainingRequest request, @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException;

    /** Request a pause at an optimizer boundary. Only a Paused result confirms durable saving. */
    void requestPause(@NonNull Path outputDirectory) throws IOException;

    sealed interface Result permits Completed, Paused {
        @NonNull Duration elapsed();
    }

    /** An exported artifact, not an evaluated or installed model. */
    record Completed(@NonNull Path gguf, @NonNull Duration elapsed) implements Result {}

    /** A complete checkpoint; no merged model or GGUF was produced by this invocation. */
    record Paused(@NonNull Path checkpoint, @NonNull Duration elapsed) implements Result {}
}
