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
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
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
})
public sealed interface IpcFrame {

    /** User typed a command or plain-text prompt. */
    record Request(String raw) implements IpcFrame {}

    /** User replied to a backend-issued prompt. */
    record Input(String raw) implements IpcFrame {}

    /**
     * Tab-completion query. Backend responds with a {@link Done} carrying newline-separated
     * suggestions.
     */
    record Complete(String raw) implements IpcFrame {}

    /** User interrupted the current request (Ctrl+C). */
    record Cancel() implements IpcFrame {}

    /** Terminal is shutting down cleanly — backend should release resources. */
    record Bye() implements IpcFrame {}

    /** Periodic keep-alive from the terminal. Backend treats silence as a dead terminal. */
    record Heartbeat() implements IpcFrame {}

    /**
     * Requests a placeholder hint for the next expected argument. The backend responds with a
     * {@link Done} whose {@code content} is the placeholder text (e.g. {@code <name>}) and optional
     * {@code meta.description} giving context.
     */
    record Hint(String raw) implements IpcFrame {}

    /** Streaming content chunk — not terminal; more frames follow. */
    record Delta(String content, int index) implements IpcFrame {}

    /** Terminal frame — response complete. The session state lives in {@code meta}. */
    record Done(java.util.Map<String, Object> meta, String content) implements IpcFrame {
        public Done(Map<String, Object> meta) {
            this(meta, null);
        }

        public Done() {
            this(Map.of(), null);
        }
    }

    /** Fatal — terminates the exchange with an error message. */
    record Error(String content) implements IpcFrame {}

    /**
     * Backend requests user input — pauses the stream; terminal writes an {@link Input} frame in
     * reply.
     */
    record Prompt(String content, Map<String, Object> meta) implements IpcFrame {
        public Prompt(String content) {
            this(content, Map.of());
        }
    }

    /** Optional progress hint between deltas. */
    record Progress(String content, int percent) implements IpcFrame {
        public Progress(String content) {
            this(content, -1);
        }
    }

    /** Convenience: create a terminal-frame done with a string content (for completion results). */
    static Done doneContent(String content) {
        return new Done(Map.of(), content);
    }

    /** Convenience: create a simple error. */
    static Error ofError(String msg) {
        return new Error(msg);
    }
}
