package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Synchronous text boundary before user input or file content is committed or exposed to a model.
 * Listeners may replace {@link #text()}; prevention or listener failure stops publication.
 */
public final class BeforeTextCommitEvent extends WorkflowEvent {
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

    /** Creates a host-attributed text event for one selected session and agent. */
    public BeforeTextCommitEvent(
            @NonNull String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull Phase phase,
            @NonNull String sourceId,
            @NonNull String text) {
        super(owner, sessionId, agentId, cancellation);
        this.phase = phase;
        this.sourceId = sourceId;
        this.text = text;
    }

    /** Returns the host-defined text boundary. */
    public @NonNull Phase phase() {
        return phase;
    }

    /** Returns the identity of this individual source. */
    public @NonNull String sourceId() {
        return sourceId;
    }

    /** Returns the current text after preceding listeners. */
    public @NonNull String text() {
        return text;
    }

    /** Replaces the text before the host publishes it. */
    public void setText(@NonNull String text) {
        this.text = text;
        this.replaced = true;
    }

    /** Reports whether a listener supplied a replacement observation. */
    public boolean replaced() {
        return replaced;
    }
}
