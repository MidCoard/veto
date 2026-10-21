package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonModelTrainerTest {
    @Test
    void taskConfigurationsShareTheJavaEngineButKeepArtifactsAndPromptFormatsSeparate(
            @TempDir @NonNull Path root) throws Exception {
        var trainer = trainer(root, Duration.ofSeconds(15));
        var repositoryPath = System.getProperty("veto.repository");
        if (repositoryPath == null) throw new AssertionError("Missing repository property");
        var repository = Path.of(repositoryPath);
        var artifacts = new ArrayList<Path>();
        for (String task :
                List.of(
                        "veto-core/training/screening.properties",
                        "veto-secret-protection/training/secret.properties")) {
            var properties = new Properties();
            try (var input = Files.newInputStream(repository.resolve(task))) {
                properties.load(input);
            }
            String name = properties.getProperty("model.name");
            if (name == null) throw new AssertionError("Missing model name");
            properties.setProperty(
                    "training.data",
                    Files.writeString(root.resolve(name + "-train.jsonl"), "{}").toString());
            properties.setProperty(
                    "validation.data",
                    Files.writeString(root.resolve(name + "-validation.jsonl"), "{}").toString());
            properties.setProperty(
                    "output.directory", root.resolve("outputs").resolve(name).toString());
            var request = TrainingCli.request(root, properties);
            var result = trainer.train(request, line -> {});
            artifacts.add(result.gguf());
            assertEquals(Path.of(name + "-q4_k_m.gguf"), result.gguf().getFileName());
            assertEquals(
                    properties.getProperty("prompt.format"),
                    Files.readString(request.outputDirectory().resolve("format.txt")));
            assertEquals("fixture", Files.readString(result.gguf()));
            assertFalse(Files.exists(root.resolve("models")));
            assertThrows(IOException.class, () -> trainer.train(request, line -> {}));
            assertEquals("fixture", Files.readString(result.gguf()));
        }
        assertNotEquals(artifacts.get(0), artifacts.get(1));
    }

    @Test
    void failedWorkerOrMissingArtifactCannotReturnSuccess(@TempDir @NonNull Path root)
            throws Exception {
        var trainer = trainer(root, Duration.ofSeconds(15));
        for (String mode : List.of("fail", "missing")) {
            assertThrows(IOException.class, () -> trainer.train(request(root, mode), line -> {}));
        }
    }

    @Test
    void rejectsTrainingValidationOverlapBeforeCreatingOutput(@TempDir @NonNull Path root)
            throws Exception {
        var trainer = trainer(root, Duration.ofSeconds(15));
        var request = request(root, "overlap");
        var invalid =
                new TrainingRequest(
                        request.baseModel(),
                        request.trainingData(),
                        request.trainingData(),
                        request.outputDirectory(),
                        request.modelName(),
                        request.options());
        assertThrows(IllegalArgumentException.class, () -> trainer.train(invalid, line -> {}));
        assertFalse(Files.exists(invalid.outputDirectory()));
    }

    @Test
    void timeoutRetiresSilentWorker(@TempDir @NonNull Path root) throws Exception {
        var trainer = trainer(root, Duration.ofSeconds(3));
        var pid = new AtomicReference<Long>();
        var childPid = new AtomicReference<Long>();
        assertThrows(
                IOException.class,
                () ->
                        trainer.train(
                                request(root, "sleep"),
                                line -> {
                                    if (line.startsWith("pid="))
                                        pid.set(Long.parseLong(line.substring(4)));
                                    if (line.startsWith("child="))
                                        childPid.set(Long.parseLong(line.substring(6)));
                                }));
        assertFalse(
                ProcessHandle.of(Objects.requireNonNull(pid.get()))
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
        assertFalse(
                ProcessHandle.of(Objects.requireNonNull(childPid.get()))
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
    }

    @Test
    void interruptionAndProgressFailureRetireWorker(@TempDir @NonNull Path root) throws Exception {
        var trainer = trainer(root, Duration.ofSeconds(15));
        var request = request(root, "sleep");
        var started = new CompletableFuture<Long>();
        var failure = new AtomicReference<Throwable>();
        var caller =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        trainer.train(
                                                request,
                                                line -> {
                                                    if (line.startsWith("pid=")
                                                            && !started.complete(
                                                                    Long.parseLong(
                                                                            line.substring(4)))) {
                                                        throw new IllegalStateException(
                                                                "Unexpected duplicate worker start");
                                                    }
                                                });
                                    } catch (Throwable e) {
                                        failure.set(e);
                                    }
                                });
        try {
            long pid = started.get(10, TimeUnit.SECONDS);
            caller.interrupt();
            caller.join(7000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            caller.interrupt();
            caller.join(7000);
        }
        var pid = new AtomicReference<Long>();
        var second = request(root, "listener");
        assertThrows(
                IOException.class,
                () ->
                        trainer.train(
                                second,
                                line -> {
                                    if (line.startsWith("pid=")) {
                                        pid.set(Long.parseLong(line.substring(4)));
                                        throw new IllegalStateException("listener failure");
                                    }
                                }));
        assertFalse(
                ProcessHandle.of(Objects.requireNonNull(pid.get()))
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
    }

    private static @NonNull PythonModelTrainer trainer(
            @NonNull Path root, @NonNull Duration timeout) throws Exception {
        Files.writeString(root.resolve("train.py"), "fixture");
        Files.writeString(root.resolve("convert_to_gguf.py"), "fixture");
        Files.writeString(root.resolve("convert_hf_to_gguf.py"), "fixture");
        var source = Worker.class.getProtectionDomain().getCodeSource();
        if (source == null) throw new AssertionError("Missing fixture code source");
        var location = source.getLocation();
        var classpath = Path.of(location.toURI()).toString();
        var java =
                Path.of(Objects.requireNonNull(System.getProperty("java.home")), "bin", "java")
                        .toString();
        return new PythonModelTrainer(
                List.of(java, "-cp", classpath, Worker.class.getName()), root, root, root, timeout);
    }

    private static @NonNull TrainingRequest request(@NonNull Path root, @NonNull String mode)
            throws IOException {
        return new TrainingRequest(
                mode,
                Files.writeString(root.resolve(mode + "-train.jsonl"), "{}"),
                Files.writeString(root.resolve(mode + "-validation.jsonl"), "{}"),
                root.resolve(mode),
                "model",
                new TrainingRequest.Options(
                        1, 0.0001, 1, 1, 16, 32, 0.05, 256, TrainingRequest.PromptFormat.RAW));
    }

    /** A real child process replacing Python; no GPU, dependencies or model writes. */
    public static final class Worker {
        private Worker() {}

        public static void main(@NonNull String @NonNull [] args) throws Exception {
            if (args[0].equals("leaf")) {
                Thread.sleep(60000);
                return;
            }
            var arguments = List.of(args);
            var output = Path.of(arguments.get(arguments.indexOf("--output-dir") + 1));
            if (args[0].endsWith("train.py")) {
                String model = arguments.get(arguments.indexOf("--base-model") + 1);
                if (model.equals("fail")) System.exit(2);
                if (model.equals("sleep")) {
                    var java =
                            Path.of(
                                            Objects.requireNonNull(System.getProperty("java.home")),
                                            "bin",
                                            "java")
                                    .toString();
                    var child =
                            new ProcessBuilder(
                                            java,
                                            "-cp",
                                            Objects.requireNonNull(
                                                    System.getProperty("java.class.path")),
                                            Worker.class.getName(),
                                            "leaf")
                                    .inheritIO()
                                    .start();
                    System.out.println("child=" + child.pid());
                }
                System.out.println("pid=" + ProcessHandle.current().pid());
                System.out.flush();
                if (model.equals("sleep") || model.equals("listener")) Thread.sleep(60000);
                Files.createDirectories(output.resolve("merged"));
                Files.writeString(
                        output.resolve("format.txt"),
                        arguments.get(arguments.indexOf("--prompt-format") + 1));
                Files.writeString(output.resolve("merged/mode"), model);
            } else {
                var merged = Path.of(arguments.get(arguments.indexOf("--model-dir") + 1));
                if (Files.readString(merged.resolve("mode")).equals("missing")) return;
                Files.createDirectories(output);
                String name = arguments.get(arguments.indexOf("--model-name") + 1);
                Files.writeString(output.resolve(name + "-q4_k_m.gguf"), "fixture");
            }
        }
    }
}
