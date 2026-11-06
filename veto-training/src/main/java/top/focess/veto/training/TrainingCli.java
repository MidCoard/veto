package top.focess.veto.training;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.jspecify.annotations.NonNull;
import top.focess.veto.training.ModelTrainer.Completed;
import top.focess.veto.training.ModelTrainer.Paused;

/** Shared Java entry point for the two offline Gradle training tasks. */
public final class TrainingCli {
    private TrainingCli() {}

    public static void main(@NonNull String @NonNull [] args)
            throws IOException, InterruptedException {
        if (args.length != 2)
            throw new IllegalArgumentException("Expected repository root and task properties file");
        var root = Path.of(args[0]).toAbsolutePath().normalize();
        var properties = new Properties();
        try (var input = Files.newBufferedReader(root.resolve(args[1]))) {
            properties.load(input);
        }
        var trainer =
                new PythonModelTrainer(
                        List.of(root.resolve(required(properties, "python")).toString()),
                        root.resolve("veto-training/python"),
                        root,
                        root.resolve(required(properties, "llama.cpp")),
                        Duration.ofSeconds(
                                Long.parseLong(
                                        properties.getProperty("timeout.seconds", "14400"))));
        var request = request(root, properties);
        String action = properties.getProperty("action", "train");
        if (action.equals("pause")) {
            trainer.requestPause(request.outputDirectory());
            System.out.println("Pause requested; wait for the training caller's Paused result.");
            return;
        }
        var result =
                switch (action) {
                    case "train" -> trainer.train(request, System.out::println);
                    case "resume" ->
                            trainer.resume(
                                    request,
                                    root.resolve(required(properties, "resume.checkpoint")),
                                    System.out::println);
                    case "export" -> trainer.export(request, System.out::println);
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown training action: " + action);
                };
        switch (result) {
            case Completed completed -> System.out.println("Artifact: " + completed.gguf());
            case Paused paused -> System.out.println("Paused checkpoint: " + paused.checkpoint());
        }
        System.out.println("Elapsed: " + result.elapsed());
    }

    @SuppressWarnings("ConstantValue") // Checker treats nested-enum valueOf as nullable.
    public static @NonNull TrainingRequest request(
            @NonNull Path root, @NonNull Properties properties) {
        var format =
                TrainingRequest.PromptFormat.valueOf(
                        required(properties, "prompt.format").toUpperCase(Locale.ROOT));
        if (format == null) throw new IllegalArgumentException("Missing prompt format");
        return new TrainingRequest(
                required(properties, "base.model"),
                root.resolve(required(properties, "training.data")),
                root.resolve(required(properties, "validation.data")),
                root.resolve(required(properties, "output.directory")),
                required(properties, "model.name"),
                new TrainingRequest.Options(
                        Integer.parseInt(properties.getProperty("epochs", "3")),
                        Double.parseDouble(properties.getProperty("learning.rate", "0.0001")),
                        Integer.parseInt(properties.getProperty("batch.size", "4")),
                        Integer.parseInt(properties.getProperty("accumulation.steps", "2")),
                        Integer.parseInt(properties.getProperty("lora.rank", "16")),
                        Integer.parseInt(properties.getProperty("lora.alpha", "32")),
                        Double.parseDouble(properties.getProperty("lora.dropout", "0.05")),
                        Integer.parseInt(properties.getProperty("sequence.length", "1024")),
                        format,
                        Integer.parseInt(properties.getProperty("checkpoint.steps", "100"))));
    }

    private static @NonNull String required(@NonNull Properties properties, @NonNull String name) {
        var value = properties.getProperty(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing training property: " + name);
        return value.trim();
    }
}
