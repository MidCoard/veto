package top.focess.veto.secret.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.secret.detection.MdcSecretDetectionModel;
import top.focess.veto.secret.detection.SecretMasker;
import top.focess.veto.secret.detection.SlmSecretDetector;
import top.focess.veto.veto.GBNFGrammarEngine;
import top.focess.veto.veto.LlamaCppBridge;
import top.focess.veto.veto.SlmConfiguration;

/** Offline synthetic-data tooling using the real prompt compiler and secret detector. */
public final class SecretSftTool {
    private SecretSftTool() {}

    /** Export compiled training prompts, or measure a GGUF through the production detector. */
    public static void main(String @NonNull [] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("input output [model executable]");
        var mapper = new ObjectMapper();
        var output = new ArrayList<String>();
        LlamaCppBridge bridge = null;
        if (args.length == 4) {
            var config = new SlmConfiguration();
            config.setModelPath(args[2]);
            config.setExecutablePath(args[3]);
            bridge = new LlamaCppBridge(config, new GBNFGrammarEngine(config));
            if (!bridge.start()) throw new IllegalStateException("Secret GGUF did not start");
        }
        try {
            for (String line : Files.readAllLines(Path.of(args[0]))) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                if (!(node instanceof ObjectNode row) || !row.path("synthetic").asBoolean())
                    throw new IllegalArgumentException(
                            "Only the generated synthetic corpus is accepted");
                String text = row.path("text").asText();
                row.put(
                        "instruction",
                        PromptCompiler.compileDocument("secret-detection", Map.of("text", text))
                                .text());
                row.set(
                        "ruleValues",
                        mapper.valueToTree(values(text, SecretMasker.credentialMatches(text))));
                if (bridge != null) {
                    var port = completion(bridge, row);
                    var detector =
                            new SlmSecretDetector(
                                    new MdcSecretDetectionModel(
                                            port,
                                            (source, data) ->
                                                    PromptCompiler.compileDocument(source, data)
                                                            .text()));
                    long started = System.nanoTime();
                    row.set(
                            "detectedValues",
                            mapper.valueToTree(values(text, detector.detect(text))));
                    row.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0);
                }
                output.add(mapper.writeValueAsString(row));
            }
            Path target = Path.of(args[1]).toAbsolutePath();
            var parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, output);
        } finally {
            if (bridge != null) bridge.stop();
        }
    }

    private static @NonNull List<@NonNull String> values(
            @NonNull String text, @NonNull List<SecretMasker.SecretMatch> spans) {
        var values = new ArrayList<String>();
        for (var span : spans) {
            if (span != null) values.add(text.substring(span.start(), span.end()));
        }
        return values;
    }

    private static @NonNull LocalModelCompletion completion(
            @NonNull LlamaCppBridge bridge, @NonNull ObjectNode row) {
        return new LocalModelCompletion() {
            public boolean isAvailable() {
                return bridge.isAvailable();
            }

            public @NonNull Optional<String> complete(@NonNull Request request) {
                var pending = bridge.inferWithGrammar(request.prompt(), request.grammar());
                try {
                    String response = pending.get(2, TimeUnit.SECONDS);
                    row.put("modelResponse", response);
                    return Optional.ofNullable(response);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } catch (Exception unavailable) {
                    return Optional.empty();
                } finally {
                    if (!pending.isDone() && pending.cancel(true)) row.put("modelCancelled", true);
                }
            }
        };
    }
}
