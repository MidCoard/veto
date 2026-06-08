package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/**
 * Sealed hierarchy for all IPC protocol frames. Each terminal ↔ backend exchange uses one of these
 * frame types. The terminal-to-backend frames carry raw user input; the backend-to-terminal frames
 * carry structured responses.
 *
 * <p>Discriminated by the {@code type} field in JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = IpcFrame.Unknown.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = IpcFrame.Request.class, name = "request"),
    @JsonSubTypes.Type(value = IpcFrame.Input.class, name = "input"),
    @JsonSubTypes.Type(value = IpcFrame.Complete.class, name = "complete"),
    @JsonSubTypes.Type(value = IpcFrame.Cancel.class, name = "cancel"),
    @JsonSubTypes.Type(value = IpcFrame.Bye.class, name = "bye"),
    @JsonSubTypes.Type(value = IpcFrame.Heartbeat.class, name = "heartbeat"),
    @JsonSubTypes.Type(value = IpcFrame.Hint.class, name = "hint"),
    @JsonSubTypes.Type(value = IpcFrame.Delta.class, name = "delta"),
    @JsonSubTypes.Type(value = IpcFrame.Done.class, name = "done"),
    @JsonSubTypes.Type(value = IpcFrame.Error.class, name = "error"),
    @JsonSubTypes.Type(value = IpcFrame.Prompt.class, name = "prompt"),
    @JsonSubTypes.Type(value = IpcFrame.Progress.class, name = "progress"),
    @JsonSubTypes.Type(value = IpcFrame.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = IpcFrame.Welcome.class, name = "welcome"),
})
public sealed interface IpcFrame {

    /** Current protocol version. Increment when frame types or semantics change incompatibly. */
    int PROTOCOL_VERSION = 1;

    /**
     * User typed a command or plain-text prompt.
     *
     * @param seq monotonic sequence number for correlating with the terminal {@link Done} or {@link
     *     Error} response
     */
    record Request(String raw, long seq) implements IpcFrame {
        public Request(String raw) {
            this(raw, 0);
        }
    }

    /** User replied to a backend-issued prompt. */
    record Input(String raw) implements IpcFrame {}

    /**
     * Tab-completion query. Backend responds with a {@link Done} carrying newline-separated
     * suggestions.
     *
     * @param seq monotonic sequence number for correlating with the response
     */
    record Complete(String raw, long seq) implements IpcFrame {
        public Complete(String raw) {
            this(raw, 0);
        }
    }

    /** User interrupted the current request (Ctrl+C). */
    record Cancel() implements IpcFrame {}

    /** Terminal is shutting down cleanly — backend should release resources. */
    record Bye() implements IpcFrame {}

    /** Periodic keep-alive from the terminal. Backend treats silence as a dead terminal. */
    record Heartbeat() implements IpcFrame {}

    /**
     * Protocol handshake sent by the terminal on connect. The backend responds with a {@link
     * Welcome} at the highest version it supports that is ≤ this version.
     */
    record Hello(int version) implements IpcFrame {}

    /**
     * Backend handshake response. Carries the negotiated protocol version. If no compatible version
     * exists the backend sends an {@link Error} instead.
     */
    record Welcome(int version) implements IpcFrame {}

    /**
     * Requests a placeholder hint for the next expected argument. The backend responds with a
     * {@link Done} whose {@code content} is the placeholder text (e.g. {@code <name>}) and optional
     * {@code meta.description} giving context.
     *
     * @param seq monotonic sequence number for correlating with the hint response
     */
    record Hint(String raw, long seq) implements IpcFrame {
        public Hint(String raw) {
            this(raw, 0);
        }
    }

    /** Streaming content chunk — not terminal; more frames follow. */
    record Delta(String content, int index) implements IpcFrame {}

    /**
     * Terminal frame — response complete. The session state lives in {@code meta}.
     *
     * @param seq echoed from the initiating {@link Request}, {@link Hint}, or {@link Complete}; 0
     *     when not correlated to a specific request
     */
    record Done(java.util.Map<String, Object> meta, String content, long seq) implements IpcFrame {
        public Done(Map<String, Object> meta, String content) {
            this(meta, content, 0);
        }

        public Done(Map<String, Object> meta) {
            this(meta, null, 0);
        }

        public Done() {
            this(Map.of(), null, 0);
        }
    }

    /**
     * Fatal — terminates the exchange with an error message.
     *
     * @param seq echoed from the initiating {@link Request}; 0 when not correlated
     */
    record Error(String content, long seq) implements IpcFrame {
        public Error(String content) {
            this(content, 0);
        }
    }

    /**
     * Backend requests user input — pauses the stream; terminal writes an {@link Input} frame in
     * reply.
     */
    record Prompt(String content, Map<String, Object> meta) implements IpcFrame {
        public Prompt(String content) {
            this(content, Map.of());
        }
    }

    /**
     * Optional progress hint between deltas.
     *
     * @param percent completion percentage 0–100, or {@link #INDETERMINATE} when unknown
     */
    record Progress(String content, int percent) implements IpcFrame {
        /** Value for {@link #percent} when progress cannot be expressed as a percentage. */
        public static final int INDETERMINATE = -1;

        public Progress(String content) {
            this(content, INDETERMINATE);
        }

        /** True when this progress indicator has no known completion percentage. */
        public boolean isIndeterminate() {
            return percent == INDETERMINATE;
        }
    }

    /**
     * Fallback for unrecognized frame types — used when the remote peer speaks a newer protocol
     * version with frame types this side does not understand. Carries the raw {@code type}
     * discriminator and any deserialized fields so handlers can log-and-skip gracefully instead of
     * crashing on deserialization.
     */
    record Unknown(String type, Map<String, Object> fields) implements IpcFrame {}

    /** Convenience: create a terminal-frame done with a string content (for completion results). */
    static Done doneContent(String content) {
        return new Done(Map.of(), content, 0);
    }

    /** Convenience: create a terminal-frame done with content and sequence number. */
    static Done doneContent(String content, long seq) {
        return new Done(Map.of(), content, seq);
    }

    /** Convenience: create a simple error. */
    static Error ofError(String msg) {
        return new Error(msg, 0);
    }

    /** Convenience: create an error with a sequence number. */
    static Error ofError(String msg, long seq) {
        return new Error(msg, seq);
    }
}
