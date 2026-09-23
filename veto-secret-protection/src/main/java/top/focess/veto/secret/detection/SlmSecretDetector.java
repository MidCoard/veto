package top.focess.veto.secret.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.secret.api.SecretDetectionModel;

/**
 * SLM-primary secret detection: the model decides what is a secret; span math stays deterministic
 * so the model cannot corrupt offsets. Model spans are unioned with the deterministic rules —
 * credential categories for capture, the full rule set (including mask-only sensitive data) for
 * masking. When no model is granted or its output is unusable, detection degrades to the
 * deterministic rules alone (logged once).
 */
public final class SlmSecretDetector implements SecretDetector {
    private static final int MAX_PROMPT_CHARS = 2000;
    private static final int MIN_SECRET_LENGTH = 4;
    private static final System.@NonNull Logger log =
            System.getLogger("top.focess.veto.secret.detection.SlmSecretDetector");

    private final @Nullable SecretDetectionModel model;
    private volatile boolean degradedLogged;

    public SlmSecretDetector(@Nullable SecretDetectionModel model) {
        this.model = model;
    }

    @Override
    public @NonNull List<SecretMasker.SecretMatch> detect(@NonNull String text) {
        return union(SecretMasker.credentialMatches(text), modelSpans(text));
    }

    @Override
    public @NonNull List<SecretMasker.SecretMatch> detectAll(@NonNull String text) {
        return union(SecretMasker.matches(text), modelSpans(text));
    }

    private @NonNull List<SecretMasker.SecretMatch> modelSpans(@NonNull String text) {
        var model = this.model;
        if (model != null && model.isAvailable()) {
            try {
                var response =
                        model.complete(
                                "secret-detection",
                                Map.of(
                                        "text",
                                        text.substring(
                                                0, Math.min(text.length(), MAX_PROMPT_CHARS))));
                if (response.isPresent()) {
                    var spans = spans(text, response.get());
                    if (!spans.isEmpty()) return spans;
                }
            } catch (RuntimeException failure) {
                // Unusable model output degrades to deterministic detection.
            }
        }
        logDegraded();
        return List.of();
    }

    private void logDegraded() {
        if (!degradedLogged) {
            degradedLogged = true;
            log.log(
                    System.Logger.Level.WARNING,
                    "Secret detection degraded to deterministic patterns");
        }
    }

    /** Deterministic spans win on overlap, keeping their precise categories and markers. */
    private static @NonNull List<SecretMasker.SecretMatch> union(
            @NonNull List<SecretMasker.SecretMatch> deterministic,
            @NonNull List<SecretMasker.SecretMatch> modelSpans) {
        if (modelSpans.isEmpty()) return deterministic;
        List<SecretMasker.SecretMatch> all = new ArrayList<>(deterministic);
        all.addAll(modelSpans);
        all.sort(
                Comparator.comparingInt(SecretMasker.SecretMatch::start)
                        .thenComparing(
                                Comparator.comparingInt(SecretMasker.SecretMatch::end).reversed()));
        List<SecretMasker.SecretMatch> result = new ArrayList<>();
        for (var candidate : all) {
            if (result.isEmpty() || candidate.start() >= result.getLast().end())
                result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static @NonNull List<SecretMasker.SecretMatch> spans(
            @NonNull String text, @NonNull String response) {
        List<SecretMasker.SecretMatch> matches = new ArrayList<>();
        for (String value : parseArray(response)) {
            if (value.length() < MIN_SECRET_LENGTH) continue;
            for (int index = text.indexOf(value);
                    index >= 0;
                    index = text.indexOf(value, index + value.length()))
                matches.add(
                        new SecretMasker.SecretMatch(
                                index, index + value.length(), "slm-detected"));
        }
        matches.sort(
                Comparator.comparingInt(SecretMasker.SecretMatch::start)
                        .thenComparing(
                                Comparator.comparingInt(SecretMasker.SecretMatch::end).reversed()));
        List<SecretMasker.SecretMatch> result = new ArrayList<>();
        for (var candidate : matches) {
            if (result.isEmpty() || candidate.start() >= result.getLast().end())
                result.add(candidate);
        }
        return List.copyOf(result);
    }

    /** Parses the first JSON string array in the response; any deviation yields an empty list. */
    private static @NonNull List<String> parseArray(@NonNull String response) {
        int start = response.indexOf('[');
        if (start < 0) return List.of();
        List<String> values = new ArrayList<>();
        var current = new StringBuilder();
        boolean inString = false, escaped = false, completed = false;
        for (int position = start + 1; position < response.length(); position++) {
            char character = response.charAt(position);
            if (escaped) {
                switch (character) {
                    case '"' -> current.append('"');
                    case '\\' -> current.append('\\');
                    case '/' -> current.append('/');
                    case 'b' -> current.append('\b');
                    case 'f' -> current.append('\f');
                    case 'n' -> current.append('\n');
                    case 'r' -> current.append('\r');
                    case 't' -> current.append('\t');
                    case 'u' -> {
                        if (position + 4 >= response.length()) return List.of();
                        try {
                            current.append(
                                    (char)
                                            Integer.parseInt(
                                                    response.substring(position + 1, position + 5),
                                                    16));
                        } catch (NumberFormatException failure) {
                            return List.of();
                        }
                        position += 4;
                    }
                    default -> {
                        return List.of();
                    }
                }
                escaped = false;
            } else if (inString) {
                if (character == '\\') escaped = true;
                else if (character == '"') {
                    values.add(current.toString());
                    current.setLength(0);
                    inString = false;
                    completed = true;
                } else current.append(character);
            } else if (character == '"') {
                inString = true;
            } else if (character == ']') {
                return completed ? List.copyOf(values) : List.of();
            } else if (character != ',' && !Character.isWhitespace(character)) {
                return List.of();
            }
        }
        return List.of();
    }
}
