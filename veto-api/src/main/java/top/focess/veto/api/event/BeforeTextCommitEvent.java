package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Synchronous text boundary before user input or file content is committed or exposed to a model.
 * Listeners may replace {@link #text()}; action cancellation or listener failure stops publication.
 * {@link #prevent()} controls propagation only.
 */
public final class BeforeTextCommitEvent extends WorkflowEvent implements Cancellable {
    /** The host operation whose text is about to cross a persistence or model boundary. */
    public enum Phase {
        /** User input before it is recorded. */
        INPUT,
        /** File content while an admitted workspace read prepares its result. */
        FILE_CAPTURE,
        /** File tool result before final ingress defense and history publication. */
        FILE_OBSERVATION
    }

    private final @NonNull Phase phase;
    private final @NonNull String sourceId;
    private @NonNull String text;
    private boolean replaced;
    private boolean cancelled;

    /**
     * Creates a host-attributed text event for one selected session and agent.
     *
     * @param scope authenticated owner, session and agent identity
     * @param cancellation request cancellation signal
     * @param phase text publication boundary
     * @param sourceId individual source identity
     * @param text original text
     */
    public BeforeTextCommitEvent(
            Scope.@NonNull AgentScope scope,
            @NonNull Cancellation cancellation,
            @NonNull Phase phase,
            @NonNull String sourceId,
            @NonNull String text) {
        super(scope, cancellation);
        this.phase = phase;
        this.sourceId = sourceId;
        this.text = text;
    }

    /**
     * Returns host-defined text boundary.
     *
     * @return host-defined text boundary
     */
    public @NonNull Phase phase() {
        return phase;
    }

    /**
     * Returns identity of this individual source.
     *
     * @return identity of this individual source
     */
    public @NonNull String sourceId() {
        return sourceId;
    }

    /**
     * Returns current text after preceding listeners.
     *
     * @return current text after preceding listeners
     */
    public @NonNull String text() {
        return text;
    }

    /**
     * Replaces the text before the host publishes it.
     *
     * @param text replacement text
     */
    public void setText(@NonNull String text) {
        this.text = text;
        this.replaced = true;
    }

    /**
     * Returns whether a listener supplied replacement text.
     *
     * @return whether a listener supplied replacement text
     */
    public boolean replaced() {
        return replaced;
    }

    /**
     * Returns whether the producer should cancel text publication after delivery.
     *
     * @return current reversible action cancellation
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Sets reversible action cancellation without changing propagation or the host stop signal.
     *
     * @param cancelled whether the producer should cancel text publication
     */
    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
