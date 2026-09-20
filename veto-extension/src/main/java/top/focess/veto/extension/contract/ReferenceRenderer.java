package top.focess.veto.extension.contract;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

/** A reference mount with plugin-owned initial UI and action handler; never a model tool. */
public record ReferenceRenderer(
        @NonNull String tokenType, @NonNull PluginView initialView, @NonNull Handler handler) {
    public ReferenceRenderer {
        if (!tokenType.matches("[A-Z][A-Z0-9_]{0,47}"))
            throw new IllegalArgumentException("Invalid reference type");
    }

    public record Scope(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {}

    @FunctionalInterface
    public interface Handler {
        @NonNull Optional<PluginView> handle(
                @NonNull Scope scope, @NonNull String reference, @NonNull String action)
                throws ExtensionFailure;
    }
}
