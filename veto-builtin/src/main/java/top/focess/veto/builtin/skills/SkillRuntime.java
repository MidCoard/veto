package top.focess.veto.builtin.skills;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.resources.CatalogueAccess;
import top.focess.veto.api.resources.CatalogueTree;

/** Builtin owns skill selection, discovery and durable integrity anchors. */
public final class SkillRuntime implements AutoCloseable {
    private final @Nullable CatalogueAccess resources;
    private final @Nullable PluginStorage storage;
    private final @Nullable PluginHost host;
    private final @NonNull String projectDirectory;
    private final @NonNull Map<String, Map<String, Skill>> catalogues = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private static final @NonNull YAMLMapper YAML = new YAMLMapper();

    public SkillRuntime(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        resources = context.service(ToolDocs.nonNullClass(CatalogueAccess.class)).orElse(null);
        storage = context.service(ToolDocs.nonNullClass(PluginStorage.class)).orElse(null);
        host = context.service(ToolDocs.nonNullClass(PluginHost.class)).orElse(null);
        var configured = configuration.values().get("skills-project-directory");
        projectDirectory =
                configured instanceof JsonValue.StringValue value && !value.value().isBlank()
                        ? value.value()
                        : ".veto/skills";
    }

    public @NonNull List<CatalogueItem> catalogue(@NonNull CatalogueTree workspace) {
        return selected(workspace).values().stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(skill -> new CatalogueItem(skill.name(), skill.description()))
                .toList();
    }

    private @NonNull Map<String, Skill> selected(@NonNull CatalogueTree workspace) {
        if (closed || resources == null || storage == null) return Map.of();
        return catalogues.computeIfAbsent(workspace.identity(), ignored -> scan(workspace));
    }

    private @NonNull Map<String, Skill> scan(@NonNull CatalogueTree workspace) {
        Map<String, Skill> result = new LinkedHashMap<>();
        if (resources != null)
            resources.shared("personal").ifPresent(tree -> read(tree, "", "PERSONAL", result));
        read(workspace, projectDirectory, "PROJECT", result);
        return Map.copyOf(result);
    }

    private void read(
            @NonNull CatalogueTree tree,
            @NonNull String directory,
            @NonNull String source,
            @NonNull Map<String, Skill> result) {
        try {
            for (var file : tree.files(directory, "SKILL.md")) {
                var parsed = parse(file.identity(), file.read(), source);
                parsed.ifPresent(skill -> result.put(skill.name(), skill));
            }
        } catch (Exception failure) {
            // A failed project scan cannot silently advertise personal fallbacks.
            throw new IllegalStateException("Skill catalogue cannot be read", failure);
        }
    }

    public @NonNull Optional<String> load(@NonNull String name) {
        if (closed || resources == null || host == null || storage == null)
            throw new SecurityException("Skills are unavailable");
        host.invocation("load_skill");
        var workspace = resources.workspace();
        Skill selected = selected(workspace).get(name);
        if (selected == null) return Optional.empty();
        var current = scan(workspace).get(name);
        if (current == null
                || !current.identity().equals(selected.identity())
                || !current.rawHash().equals(selected.rawHash())) return Optional.empty();
        var store = storage.application();
        String key = "skills/hash/" + selected.identity();
        var anchor = store.get(key);
        if (anchor.isPresent()) {
            if (!(anchor.get().document().value() instanceof JsonValue.StringValue value)
                    || !value.value().equals(selected.bodyHash())) return Optional.empty();
        } else {
            try {
                store.put(
                        key,
                        null,
                        new PluginStorage.Document(
                                1, new JsonValue.StringValue(selected.bodyHash())));
            } catch (PluginStorage.Conflict concurrent) {
                var saved = store.get(key);
                if (saved.isEmpty()
                        || !(saved.get().document().value() instanceof JsonValue.StringValue value)
                        || !value.value().equals(selected.bodyHash())) return Optional.empty();
            }
        }
        return Optional.of(selected.body());
    }

    /**
     * Distribution migration only; conflicting old anchors fail closed, never replace new trust.
     */
    public void importLegacy(@NonNull String fileIdentity, @NonNull String bodyHash) {
        if (storage == null) throw new IllegalStateException("Skill storage unavailable");
        var store = storage.application();
        String key = "skills/hash/" + fileIdentity;
        var old = store.get(key);
        if (old.isPresent()) {
            if (!(old.get().document().value() instanceof JsonValue.StringValue value)
                    || !value.value().equals(bodyHash))
                throw new IllegalStateException("Conflicting legacy skill anchor");
            return;
        }
        store.put(key, null, new PluginStorage.Document(1, new JsonValue.StringValue(bodyHash)));
    }

    static @NonNull Optional<Skill> parse(
            @NonNull String identity, @NonNull String text, @NonNull String source) {
        try {
            String[] parts = text.split("(?m)^---$");
            if (parts.length < 3) return Optional.empty();
            var metadata = YAML.readTree(parts[1]);
            if (metadata == null
                    || !metadata.path("name").isTextual()
                    || !metadata.path("description").isTextual()) return Optional.empty();
            String name = metadata.path("name").asText();
            if (name.isBlank()) return Optional.empty();
            var body = new StringBuilder();
            for (int i = 2; i < parts.length; i++) body.append(parts[i]);
            String instructions = body.toString().trim();
            return Optional.of(
                    new Skill(
                            identity,
                            name,
                            metadata.path("description").asText(),
                            instructions,
                            source,
                            hash(text),
                            hash(instructions)));
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    public static @NonNull String hash(@NonNull String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void close() {
        closed = true;
        catalogues.clear();
    }

    public record CatalogueItem(@NonNull String name, @NonNull String description) {}

    record Skill(
            @NonNull String identity,
            @NonNull String name,
            @NonNull String description,
            @NonNull String body,
            @NonNull String source,
            @NonNull String rawHash,
            @NonNull String bodyHash) {}
}
