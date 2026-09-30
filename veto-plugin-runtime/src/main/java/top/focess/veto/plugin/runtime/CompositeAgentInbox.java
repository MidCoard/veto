package top.focess.veto.plugin.runtime;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Namespaces inbox identities and admits every callback through its owning plugin lifecycle. */
public final class CompositeAgentInbox extends AgentInbox {
    /** An inbox source paired with its owning plugin and identity namespace. */
    public record Entry(
            @NonNull String id, @NonNull PluginLifecycle plugin, @NonNull AgentInbox source) {}

    private final @NonNull Supplier<@NonNull List<@NonNull Entry>> entries;

    /** Creates a composite that reads its namespaced entries from the given supplier. */
    public CompositeAgentInbox(@NonNull Supplier<@NonNull List<@NonNull Entry>> entries) {
        this.entries = entries;
    }

    private static @NonNull Observation identity(
            @NonNull Observation value, @NonNull String id, @NonNull String continuation) {
        return new Observation(
                id,
                value.requestId(),
                value.content(),
                value.occurredAt(),
                value.topic(),
                value.attributes(),
                continuation);
    }

    private static @NonNull String continuation(
            @NonNull String namespace, @NonNull Observation value) {
        String key = value.continuationId();
        return "plugin-work:"
                + namespace.length()
                + ":"
                + namespace
                + ":"
                + (key == null ? value.id() : key);
    }

    private static @NonNull String rawContinuation(
            @NonNull String namespace, @NonNull Observation value) {
        String key = value.continuationId();
        String prefix = "plugin-work:" + namespace.length() + ":" + namespace + ":";
        if (key == null || !key.startsWith(prefix))
            throw new SecurityException("Unbound plugin work identity");
        return key.substring(prefix.length());
    }

    // The plugin handle is owned by the entries supplier and closed by its lifecycle owner.
    // The @NonNull bound is required so T satisfies Operation<T>.
    @SuppressWarnings({"resource", "NullableProblems"})
    private static <T extends @NonNull Object> T invoke(
            @NonNull Entry entry, PluginLifecycle.@NonNull Operation<T> action) {
        try {
            return entry.plugin().execute(action);
        } catch (PluginFailure failure) {
            throw new IllegalStateException("Plugin work unavailable", failure);
        }
    }

    @Override
    public @NonNull List<@NonNull Observation> pending(@NonNull InboxContext scope) {
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

    private void notify(
            @NonNull Observation observation,
            @NonNull BiConsumer<@NonNull AgentInbox, @NonNull Observation> action) {
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
    public void started(@NonNull InboxContext scope, @NonNull Observation value) {
        notify(value, (source, raw) -> source.started(scope, raw));
    }

    @Override
    public void completed(
            @NonNull InboxContext scope, @NonNull Observation value, boolean success) {
        notify(value, (source, raw) -> source.completed(scope, raw, success));
    }

    @Override
    public void cancelled(@NonNull InboxContext scope, @NonNull Observation value) {
        notify(value, (source, raw) -> source.cancelled(scope, raw));
    }
}
