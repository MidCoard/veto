package top.focess.veto.agent.loop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.focess.veto.llm.core.ChatMessage;

/**
 * Bundled trusted source package, recursively discovered under {@code prompts/}. Folder names
 * organize sources; globally unique file basenames remain the lookup and include IDs. No
 * missing-source or Java-text fallback is permitted.
 */
public final class PromptLibrary {
    private static final @NonNull Map<String, String> SOURCES = load();

    private PromptLibrary() {}

    public static PromptDocument.@NonNull Result compile(
            @NonNull String entry, @NonNull Map<String, ?> data) {
        return PromptDocument.compile(entry, data, SOURCES);
    }

    public static @NonNull String text(@NonNull String entry, @NonNull Map<String, ?> data) {
        return compile(entry, data).text().strip();
    }

    public static @NonNull String text(@NonNull String entry) {
        return text(entry, Map.of());
    }

    public static @NonNull ChatMessage message(
            @NonNull String entry, @NonNull Map<String, ?> data) {
        var result = compile(entry, data);
        if (result.messages().size() != 1)
            throw new IllegalArgumentException(entry + ": expected one message");
        return result.messages().getFirst();
    }

    public static @NonNull String source(@NonNull String entry) {
        String value = SOURCES.get(entry);
        if (value == null) throw new IllegalArgumentException("Unknown prompt source: " + entry);
        return value;
    }

    private static @NonNull Map<String, String> load() {
        Map<String, String> result = new TreeMap<>();
        try {
            for (var resource :
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:prompts/**/*.mdc")) {
                String filename = resource.getFilename();
                if (filename == null) throw new IllegalStateException("Unnamed prompt source");
                String key = filename.substring(0, filename.length() - 4);
                try (var input = resource.getInputStream()) {
                    if (result.putIfAbsent(
                                    key, new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            != null)
                        throw new IllegalStateException("Duplicate prompt source: " + key);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load prompt sources", failure);
        }
        return Map.copyOf(result);
    }
}
