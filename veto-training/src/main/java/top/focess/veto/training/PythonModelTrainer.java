package top.focess.veto.training;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/** Invokes the shared trainer and converter without a shell or runtime deployment. */
public final class PythonModelTrainer implements ModelTrainer {
    private final @NonNull List<@NonNull String> python;
    private final @NonNull Path scripts;
    private final @NonNull Path workingDirectory;
    private final @NonNull Path llamaCpp;
    private final @NonNull Duration timeout;

    public PythonModelTrainer(
            @NonNull List<@NonNull String> python,
            @NonNull Path scripts,
            @NonNull Path workingDirectory,
            @NonNull Path llamaCpp,
            @NonNull Duration timeout) {
        if (python.isEmpty()
                || python.getFirst().isBlank()
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "Python command and a bounded positive timeout are required");
        }
        this.python = List.copyOf(python);
        this.scripts = scripts.toAbsolutePath().normalize();
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.llamaCpp = llamaCpp.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    @Override
    public @NonNull Result train(
            @NonNull TrainingRequest request, @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException("Training cancelled before launch");
        requireFile(request.trainingData());
        requireFile(request.validationData());
        if (Files.isSameFile(request.trainingData(), request.validationData())) {
            throw new IllegalArgumentException("Training and validation files must be separate");
        }
        requireFile(scripts.resolve("train.py"));
        requireFile(scripts.resolve("convert_to_gguf.py"));
        requireFile(llamaCpp.resolve("convert_hf_to_gguf.py"));
        var output = request.outputDirectory();
        // Never reuse a run: stale results must not be reported as successful new training.
        var parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createDirectory(output);
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        var options = request.options();
        run(
                "train.py",
                List.of(
                        "--base-model", request.baseModel(),
                        "--data-path", request.trainingData().toString(),
                        "--eval-data-path", request.validationData().toString(),
                        "--output-dir", output.toString(),
                        "--epochs", Integer.toString(options.epochs()),
                        "--lr", Double.toString(options.learningRate()),
                        "--batch-size", Integer.toString(options.batchSize()),
                        "--gradient-accumulation-steps",
                                Integer.toString(options.accumulationSteps()),
                        "--lora-r", Integer.toString(options.loraRank()),
                        "--lora-alpha", Integer.toString(options.loraAlpha()),
                        "--lora-dropout", Double.toString(options.loraDropout()),
                        "--max-seq-length", Integer.toString(options.sequenceLength()),
                        "--prompt-format", options.promptFormat().name().toLowerCase(Locale.ROOT),
                        "--log-file", output.resolve("training.jsonl").toString()),
                deadline,
                progress);
        var merged = output.resolve("merged");
        if (!Files.isDirectory(merged))
            throw new IOException("Training did not produce a merged model");
        run(
                "convert_to_gguf.py",
                List.of(
                        "--model-dir", merged.toString(),
                        "--output-dir", output.resolve("gguf").toString(),
                        "--model-name", request.modelName(),
                        "--llama-cpp-dir", llamaCpp.toString(),
                        "--quantize-type", "q4_k_m",
                        "--log-file", output.resolve("conversion.jsonl").toString()),
                deadline,
                progress);
        var artifact = output.resolve("gguf").resolve(request.modelName() + "-q4_k_m.gguf");
        requireFile(artifact);
        if (Files.size(artifact) == 0)
            throw new IOException("Conversion produced an empty artifact");
        return new Result(artifact, Duration.ofNanos(System.nanoTime() - started));
    }

    private void run(
            @NonNull String script,
            @NonNull List<@NonNull String> arguments,
            long deadline,
            @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(python);
        command.add(scripts.resolve(script).toString());
        command.addAll(arguments);
        TrainingProcess.run(command, workingDirectory, deadline, progress);
    }

    private static void requireFile(@NonNull Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Missing file: " + path);
    }
}
