package top.focess.veto.training;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        return execute(request, null, progress);
    }

    @Override
    public @NonNull Result resume(
            @NonNull TrainingRequest request,
            @NonNull Path checkpoint,
            @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException {
        return execute(request, checkpoint, progress);
    }

    @Override
    public @NonNull Completed export(
            @NonNull TrainingRequest request, @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("Export cancelled before launch");
        requireFile(scripts.resolve("export.py"));
        requireFile(scripts.resolve("convert_to_gguf.py"));
        requireFile(llamaCpp.resolve("convert_hf_to_gguf.py"));
        var output = request.outputDirectory();
        if (!Files.isDirectory(output)) throw new IOException("Training output does not exist");
        try (var channel =
                        FileChannel.open(
                                output.resolve("training.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Training run is already active");
            checkWorker(output);
            long started = System.nanoTime();
            long deadline = started + timeout.toNanos();
            run(
                    "export.py",
                    List.of(
                            "--base-model", request.baseModel(),
                            "--output-dir", output.toString()),
                    deadline,
                    progress);
            return convert(request, started, deadline, true, progress);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Training run is already active", e);
        }
    }

    @Override
    public void requestPause(@NonNull Path outputDirectory) throws IOException {
        requireFile(outputDirectory.resolve("training.lock"));
        if (Files.exists(outputDirectory.resolve("merged")))
            throw new IOException("Optimization has finished; export cannot be paused");
        Files.writeString(outputDirectory.resolve("pause.request"), "pause\n");
    }

    private @NonNull Result execute(
            @NonNull TrainingRequest request,
            Path checkpoint,
            @NonNull Consumer<@NonNull String> progress)
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
        var parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (checkpoint == null) Files.createDirectory(output);
        else {
            checkpoint = checkedCheckpoint(output, checkpoint);
            if (Files.exists(output.resolve("merged")) || Files.exists(output.resolve("gguf")))
                throw new IOException("Run already reached export; choose an unfinished run");
        }
        try (var channel =
                        FileChannel.open(
                                output.resolve("training.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Training run is already active");
            checkWorker(output);
            if (checkpoint != null) {
                for (String name : List.of("pause.request", "paused.checkpoint")) {
                    if (Files.deleteIfExists(output.resolve(name)))
                        progress.accept("Cleared previous " + name);
                }
            }
            return trainAndExport(request, checkpoint, progress);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Training run is already active", e);
        }
    }

    private static void checkWorker(@NonNull Path output) throws IOException {
        var worker = output.resolve("worker.lock");
        if (Files.exists(worker)) {
            try (var workerChannel = FileChannel.open(worker, StandardOpenOption.WRITE);
                    var workerLock = workerChannel.tryLock()) {
                if (workerLock == null) throw new IOException("Training worker is still active");
            }
        }
    }

    private @NonNull Result trainAndExport(
            @NonNull TrainingRequest request,
            Path checkpoint,
            @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException {
        var output = request.outputDirectory();
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        var options = request.options();
        var arguments =
                new ArrayList<>(
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
                                "--prompt-format",
                                        options.promptFormat().name().toLowerCase(Locale.ROOT),
                                "--checkpoint-steps", Integer.toString(options.checkpointSteps()),
                                "--log-file", output.resolve("training.jsonl").toString()));
        if (checkpoint != null) {
            arguments.add("--resume-from-checkpoint");
            arguments.add(checkpoint.toString());
        }
        run("train.py", arguments, deadline, progress);
        var paused = output.resolve("paused.checkpoint");
        if (Files.isRegularFile(paused)) {
            var saved = checkedCheckpoint(output, output.resolve(Files.readString(paused).trim()));
            return new Paused(saved, Duration.ofNanos(System.nanoTime() - started));
        }
        return convert(request, started, deadline, false, progress);
    }

    private @NonNull Completed convert(
            @NonNull TrainingRequest request,
            long started,
            long deadline,
            boolean retry,
            @NonNull Consumer<@NonNull String> progress)
            throws IOException, InterruptedException {
        var output = request.outputDirectory();
        var merged = output.resolve("merged");
        if (!Files.isDirectory(merged))
            throw new IOException("Training did not produce a merged model");
        var arguments =
                new ArrayList<>(
                        List.of(
                                "--model-dir", merged.toString(),
                                "--output-dir", output.resolve("gguf").toString(),
                                "--model-name", request.modelName(),
                                "--llama-cpp-dir", llamaCpp.toString(),
                                "--quantize-type", "q4_k_m",
                                "--log-file", output.resolve("conversion.jsonl").toString()));
        if (retry) arguments.add("--retry");
        run("convert_to_gguf.py", arguments, deadline, progress);
        var artifact = output.resolve("gguf").resolve(request.modelName() + "-q4_k_m.gguf");
        requireFile(artifact);
        if (Files.size(artifact) == 0)
            throw new IOException("Conversion produced an empty artifact");
        return new Completed(artifact, Duration.ofNanos(System.nanoTime() - started));
    }

    private static @NonNull Path checkedCheckpoint(@NonNull Path output, @NonNull Path checkpoint)
            throws IOException {
        if (Files.isSymbolicLink(checkpoint))
            throw new IOException("Checkpoint must not be a link");
        var resolved = checkpoint.toRealPath();
        var name = resolved.getFileName();
        var parent = resolved.getParent();
        if (name == null
                || !name.toString().matches("checkpoint-[0-9]+")
                || parent == null
                || !Files.isSameFile(parent, output))
            throw new IOException("Checkpoint must belong to this run");
        requireFile(resolved.resolve("complete.json"));
        return resolved;
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
