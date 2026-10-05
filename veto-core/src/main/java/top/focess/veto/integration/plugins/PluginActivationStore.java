package top.focess.veto.integration.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Database-backed administrator choices applied when the backend next starts. */
@Component
public final class PluginActivationStore {
    private final @NonNull PluginActivationRepository repository;
    private final @NonNull Map<@NonNull String, @NonNull Boolean> overrides =
            new ConcurrentHashMap<>();

    /** Loads saved choices before plugin package discovery. */
    public PluginActivationStore(
            @NonNull PluginActivationRepository repository,
            @Value("${veto.plugins.directory:plugins}") @NonNull String pluginDirectory)
            throws IOException {
        this.repository = repository;
        repository.findAll().forEach(row -> overrides.put(row.getId(), row.isEnabled()));
        Path previous = Path.of(pluginDirectory).resolve(".plugin-activation");
        if (Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS))
            for (String line : Files.readAllLines(previous, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator < 1)
                    throw new IOException("Invalid previous plugin activation state");
                String id = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!value.equals("enabled") && !value.equals("disabled"))
                    throw new IOException("Invalid previous plugin activation state");
                if (!overrides.containsKey(id)) setEnabled(id, value.equals("enabled"));
            }
    }

    @NonNull Set<String> disabledIds(@NonNull Set<String> configured) {
        Set<String> result = new HashSet<>(configured);
        overrides.forEach(
                (id, enabled) -> {
                    if (enabled) result.remove(id);
                    else result.add(id);
                });
        return Set.copyOf(result);
    }

    /** Desired state, using the configured startup default until an administrator sets it. */
    public boolean desiredEnabled(@NonNull String id, @NonNull Set<String> configured) {
        return overrides.getOrDefault(id, !configured.contains(id));
    }

    /** Saves a choice without changing any running plugin instance. */
    // WHY: Checker treats the persistence return as nullable; validate the external save boundary.
    @SuppressWarnings("ConstantValue")
    public synchronized void setEnabled(@NonNull String id, boolean enabled) {
        PluginActivationEntity saved =
                repository.saveAndFlush(new PluginActivationEntity(id, enabled));
        if (saved == null || !saved.getId().equals(id) || saved.isEnabled() != enabled)
            throw new IllegalStateException("Plugin activation state was not saved");
        overrides.put(saved.getId(), saved.isEnabled());
    }
}
