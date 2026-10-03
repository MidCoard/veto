package top.focess.veto.integration.plugins;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.veto.LlamaCppBridge;

/** Lifecycle-bound local inference. Prompts and grammar contents never enter logs. */
final class BoundLocalModelCompletion implements LocalModelCompletion {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger(BoundLocalModelCompletion.class);
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull LlamaCppBridge bridge;

    BoundLocalModelCompletion(@NonNull ManagedPlugin plugin, @NonNull LlamaCppBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public boolean isAvailable() {
        return plugin.state() == PluginState.ACTIVE && bridge.isAvailable();
    }

    public @NonNull Optional<String> complete(@NonNull Request request) {
        if (!request.purpose().matches("[A-Za-z][A-Za-z0-9._-]{0,63}")
                || request.prompt().length() > 65536
                || request.grammar().length() > 8192
                || request.grammar().isBlank()
                || Thread.currentThread().isInterrupted()) return Optional.empty();
        try {
            return plugin.execute(
                    () -> {
                        if (!isAvailable()) return Optional.empty();
                        log.debug(
                                "Plugin local-model completion: {}:{}",
                                plugin.identity().id(),
                                request.purpose());
                        var pending = bridge.inferWithGrammar(request.prompt(), request.grammar());
                        try {
                            plugin.ownStoppingResource(pending, () -> pending.cancel(true));
                            String response = pending.get(2, TimeUnit.SECONDS);
                            return response != null && response.length() <= 65536
                                    ? Optional.of(response)
                                    : Optional.empty();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return Optional.empty();
                        } catch (Exception unavailable) {
                            return Optional.empty();
                        } finally {
                            pending.cancel(true);
                            plugin.releaseResource(pending);
                        }
                    });
        } catch (PluginFailure unavailable) {
            return Optional.empty();
        }
    }
}
