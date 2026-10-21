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

    /** An exported artifact, not an evaluated or installed model. */
    record Result(@NonNull Path gguf, @NonNull Duration elapsed) {}
}
