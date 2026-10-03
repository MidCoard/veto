package top.focess.veto.integration.plugins;

import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Generic model port; admission requires the same live Gateway-approved plugin invocation. */
public final class PluginTextEmbeddings implements TextEmbedding {
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull TextEmbedding model;

    /** Wraps the host embedding model with invocation-bound admission for the plugin. */
    public PluginTextEmbeddings(@NonNull ManagedPlugin plugin, @NonNull TextEmbedding model) {
        this.plugin = plugin;
        this.model = model;
    }

    @Override
    public float @NonNull [] embed(@NonNull String text) {
        var context = ToolCallContextHolder.get();
        if (plugin.state() != PluginState.ACTIVE
                || context == null
                || context.owner() == null
                || context.sessionId() == null
                || !plugin.bindingId().equals(context.executionPermit().remoteServerName())
                || !context.executionPermit().authorizesCaller(context)
                || !context.executionPermit()
                        .callId()
                        .equals(ToolCallContextHolder.currentCallId()))
            throw new SecurityException("Embedding requires an authorized plugin invocation");
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Embedding cancelled");
        if (text.length() > 64_000) throw new IllegalArgumentException("Embedding input too large");
        var vector = model.embed(text);
        if (plugin.state() != PluginState.ACTIVE || ToolCallContextHolder.get() != context)
            throw new SecurityException("Embedding invocation expired");
        if (vector.length != dimension())
            throw new IllegalStateException("Embedding dimension mismatch");
        for (float value : vector)
            if (!Float.isFinite(value))
                throw new IllegalStateException("Embedding contains non-finite values");
        return vector;
    }

    @Override
    public int dimension() {
        return model.dimension();
    }
}
