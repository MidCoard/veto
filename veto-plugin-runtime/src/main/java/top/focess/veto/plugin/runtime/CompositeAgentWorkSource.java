package top.focess.veto.plugin.runtime;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Namespaces inbox identities and admits every callback through its owning plugin lifecycle. */
@NullMarked
public final class CompositeAgentWorkSource implements AgentWorkSource {
    public record Entry(
            @NonNull String id, @NonNull ManagedPlugin plugin, @NonNull AgentWorkSource source) {}

    private final Supplier<List<Entry>> entries;

    public CompositeAgentWorkSource(Supplier<List<Entry>> entries) {
        this.entries = entries;
    }

    private static Observation identity(Observation value, String id, String continuation) {
        return new Observation(
                id,
                value.requestId(),
                value.content(),
                value.occurredAt(),
                value.topic(),
                value.attributes(),
                continuation);
    }

    private static String continuation(String namespace, Observation value) {
        String key = value.continuationId();
        return "plugin-work:"
                + namespace.length()
                + ":"
                + namespace
                + ":"
                + (key == null ? value.id() : key);
    }

    private static String rawContinuation(String namespace, Observation value) {
        String key = value.continuationId();
        String prefix = "plugin-work:" + namespace.length() + ":" + namespace + ":";
        if (key == null || !key.startsWith(prefix))
            throw new SecurityException("Unbound plugin work identity");
        return key.substring(prefix.length());
    }

    private static <T extends @NonNull Object> T invoke(
            Entry entry, ManagedPlugin.Operation<T> action) {
        try {
            return entry.plugin().execute(action);
        } catch (PluginFailure failure) {
            throw new IllegalStateException("Plugin work unavailable", failure);
        }
    }

    @Override
    public List<Observation> pending(Scope scope) {
        return entries.get().stream()
                .flatMap(
                        entry ->
                                invoke(entry, () -> entry.source().pending(scope)).stream()
                                        .map(
                                                value ->
                                                        identity(
                                                                value,
                                                                entry.id() + "/" + value.id(),
                                                                continuation(entry.id(), value))))
                .toList();
    }

    private void notify(Observation observation, BiConsumer<AgentWorkSource, Observation> action) {
        for (var entry : entries.get()) {
            String prefix = entry.id() + "/";
            if (observation.id().startsWith(prefix)) {
                invoke(
                        entry,
                        () -> {
                            action.accept(
                                    entry.source(),
                                    identity(
                                            observation,
                                            observation.id().substring(prefix.length()),
                                            rawContinuation(entry.id(), observation)));
                            return true;
                        });
                return;
            }
        }
        throw new IllegalStateException("Plugin work source unavailable");
    }

    @Override
    public void started(Scope scope, Observation value) {
        notify(value, (source, raw) -> source.started(scope, raw));
    }

    @Override
    public void completed(Scope scope, Observation value, boolean success) {
        notify(value, (source, raw) -> source.completed(scope, raw, success));
    }

    @Override
    public void cancelled(Scope scope, Observation value) {
        notify(value, (source, raw) -> source.cancelled(scope, raw));
    }
}
