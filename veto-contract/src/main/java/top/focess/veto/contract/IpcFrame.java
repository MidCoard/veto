package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Sealed hierarchy for all IPC protocol frames exchanged between the terminal (client) and the
 * backend (server).
 *
 * <h3>Directional safety</h3>
 *
 * <ul>
 *   <li>{@link ClientFrame} — frames sent <b>from the terminal to the backend</b>.
 *   <li>{@link ServerFrame} — frames sent <b>from the backend to the terminal</b>.
 *   <li>{@link Unknown} — deserialization fallback for unrecognized frame types (either direction).
 * </ul>
 *
 * <h3>Sequence number convention</h3>
 *
 * Only frames that initiate a <b>determined single-response exchange</b> carry a {@code seq}
 * number. The server echoes the same {@code seq} in its response so the client can correlate
 * request/response pairs synchronously.
 *
 * <p>Frames with indeterminate output (e.g. {@link Request} which may produce 0, 1, or many
 * streaming frames) do <b>not</b> carry a {@code seq}. Fire-and-forget frames ({@link Cancel},
 * {@link Bye}, {@link Heartbeat}, {@link Input}) also omit it.
 *
 * <p>Discriminated by the {@code type} field in JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = IpcFrame.Unknown.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = IpcFrame.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = IpcFrame.Request.class, name = "request"),
    @JsonSubTypes.Type(value = IpcFrame.Complete.class, name = "complete"),
    @JsonSubTypes.Type(value = IpcFrame.Hint.class, name = "hint"),
    @JsonSubTypes.Type(value = IpcFrame.Input.class, name = "input"),
    @JsonSubTypes.Type(value = IpcFrame.Cancel.class, name = "cancel"),
    @JsonSubTypes.Type(value = IpcFrame.Bye.class, name = "bye"),
    @JsonSubTypes.Type(value = IpcFrame.Heartbeat.class, name = "heartbeat"),
    @JsonSubTypes.Type(value = IpcFrame.Welcome.class, name = "welcome"),
    @JsonSubTypes.Type(value = IpcFrame.CompleteResult.class, name = "complete_result"),
    @JsonSubTypes.Type(value = IpcFrame.HintResult.class, name = "hint_result"),
    @JsonSubTypes.Type(value = IpcFrame.Done.class, name = "done"),
    @JsonSubTypes.Type(value = IpcFrame.Error.class, name = "error"),
    @JsonSubTypes.Type(value = IpcFrame.Delta.class, name = "delta"),
    @JsonSubTypes.Type(value = IpcFrame.Progress.class, name = "progress"),
    @JsonSubTypes.Type(value = IpcFrame.Prompt.class, name = "prompt"),
    @JsonSubTypes.Type(value = IpcFrame.Terminate.class, name = "terminate"),
})
public sealed interface IpcFrame
        permits IpcFrame.ClientFrame, IpcFrame.ServerFrame, IpcFrame.Unknown {

    /** Current protocol version. Increment when frame types or semantics change incompatibly. */
    int PROTOCOL_VERSION = 1;

    // ══════════════════════════════════════════════════════════════════════
    //  Client → Server
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Frames sent from the terminal (client) to the backend (server).
     *
     * <p>Only frames that expect a <b>determined single response</b> carry a {@code seq}: these
     * implement {@link SeqRequest}. All others are fire-and-forget or have indeterminate output.
     */
    sealed interface ClientFrame extends IpcFrame
            permits SeqRequest, Request, Input, Cancel, Bye, Heartbeat {}

    /** Marker interface for all seq-based client requests. */
    sealed interface SeqRequest extends ClientFrame permits Hello, Complete, Hint {
        /**
         * Returns the sequence number associated with this request.
         *
         * @return the sequence number
         */
        long seq();
    }

    /**
     * Protocol handshake sent by the terminal on connect. The backend responds with a {@link
     * Welcome} echoing the same {@code seq}.
     *
     * @param version the highest protocol version the client supports
     * @param seq monotonic sequence number for correlating with the {@link Welcome} response
     */
    record Hello(int version, long seq) implements SeqRequest {}

    /**
     * User typed a command or plain-text prompt.
     *
     * <p>The response is <b>indeterminate</b> — the backend may emit zero or more {@link Delta},
     * {@link Prompt}, {@link Progress} frames before a terminal {@link Done} or {@link Error}.
     * Because there is no single determined response, this frame does <b>not</b> carry a {@code
     * seq}.
     */
    record Request(@NotNull String raw) implements ClientFrame {}

    /**
     * Tab-completion query. Backend responds with exactly one {@link CompleteResult} carrying
     * newline-separated suggestions, echoing the same {@code seq}.
     *
     * @param seq monotonic sequence number for correlating with the response
     */
    record Complete(@NotNull String raw, long seq) implements SeqRequest {}

    /**
     * Requests a placeholder hint for the next expected argument. The backend responds with exactly
     * one {@link HintResult} carrying the placeholder and optional description, echoing the same
     * {@code seq}.
     *
     * @param seq monotonic sequence number for correlating with the hint response
     */
    record Hint(@NotNull String raw, long seq) implements SeqRequest {}

    /** User replied to a backend-issued {@link Prompt}. Fire-and-forget. */
    record Input(@NotNull String raw) implements ClientFrame {
        @NotNull
        @Override
        public String toString() {
            return "Input[raw=********]";
        }
    }

    /** User interrupted the current request (Ctrl+C). Fire-and-forget. */
    record Cancel() implements ClientFrame {}

    /** Terminal is shutting down cleanly — backend should release resources. Fire-and-forget. */
    record Bye() implements ClientFrame {}

    /** Periodic keep-alive from the terminal. Fire-and-forget. */
    record Heartbeat() implements ClientFrame {}

    // ══════════════════════════════════════════════════════════════════════
    //  Server → Client
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Frames sent from the backend (server) to the terminal (client).
     *
     * <p>Only frames that are <b>direct responses</b> to a sequenced client frame carry a {@code
     * seq}: these implement {@link SeqResponse}. Streaming frames ({@link Delta}, {@link Progress},
     * {@link Prompt}, {@link Done}) do not carry a {@code seq}.
     */
    sealed interface ServerFrame extends IpcFrame
            permits SeqResponse, TerminalResponse, Delta, Progress, Prompt {}

    /** Marker interface for all seq-based server response frames. */
    sealed interface SeqResponse extends ServerFrame
            permits Welcome, CompleteResult, HintResult, Error {
        long seq();
    }

    /** Sealed interface for all terminal/final responses to request frames. */
    sealed interface TerminalResponse extends ServerFrame permits Done, Error, Terminate {}

    /**
     * Backend handshake response. Carries the negotiated protocol version and echoes the {@code
     * seq} from the initiating {@link Hello}.
     *
     * @param version the negotiated protocol version
     * @param seq the sequence number echoed from the handshake request
     */
    record Welcome(int version, long seq) implements SeqResponse {}

    /**
     * An autocomplete candidate returned in a {@link CompleteResult}.
     *
     * @param value the completion suggestion text (e.g. {@code /login})
     * @param description optional helpful description or context
     * @param group optional category/group name for JLine rendering grouping
     */
    record Completion(
            @NotNull String value, @Nullable String description, @Nullable String group) {}

    /**
     * Response containing autocomplete candidates.
     *
     * @param candidates structured list of completion candidates
     * @param seq echoed from the initiating {@link Complete} request
     */
    record CompleteResult(@NotNull List<Completion> candidates, long seq) implements SeqResponse {}

    /**
     * Information about the autocomplete hint for the next expected argument.
     *
     * @param placeholder the template or placeholder representing the expected argument
     * @param description a helpful description explaining the argument
     */
    record HintInfo(@Nullable String placeholder, @Nullable String description) {
        /** Empty placeholder instance. */
        public static final HintInfo EMPTY = new HintInfo(null, null);

        /**
         * Returns the display text to render.
         *
         * @return the formatted hint display text
         */
        @NotNull
        @JsonIgnore
        public String displayText() {
            if (placeholder == null) {
                return "";
            }
            return description != null ? placeholder + " — " + description : placeholder;
        }
    }

    /**
     * Response containing next argument placeholder and description.
     *
     * @param hint next argument details (placeholder and description)
     * @param seq echoed from the initiating {@link Hint} request
     */
    record HintResult(@NotNull HintInfo hint, long seq) implements SeqResponse {}

    /**
     * Terminal frame — response complete.
     *
     * <p>Done is at the end of a sequence of Delta and Progress frames. It does not carry a
     * sequence number.
     *
     * @param meta session metadata (username, turn number, flags, etc.)
     * @param content optional content string
     */
    record Done(@NotNull Map<String, Object> meta, @Nullable String content)
            implements TerminalResponse {}

    /**
     * Fatal — terminates the exchange with an error message.
     *
     * <p>Echoes the {@code seq} from the initiating frame. When emitted in response to a {@link
     * Request} (which has no seq), use {@code seq = 0}.
     *
     * @param content error description
     * @param seq echoed from the initiating frame; 0 when not correlated to a sequenced request
     */
    record Error(@NotNull String content, long seq) implements SeqResponse, TerminalResponse {
        @NotNull
        public static Error ofError(@NotNull String content) {
            return new Error(content, 0);
        }
    }

    /**
     * Streaming content chunk — not terminal; more frames follow.
     *
     * @param content the text chunk content
     */
    record Delta(@NotNull String content) implements ServerFrame {}

    /**
     * Optional progress hint between deltas.
     *
     * @param percent completion percentage 0–100, or {@link #INDETERMINATE} when unknown
     */
    record Progress(@NotNull String content, int percent) implements ServerFrame {
        /** Value for {@link #percent} when progress cannot be expressed as a percentage. */
        public static final int INDETERMINATE = -1;

        /** True when this progress indicator has no known completion percentage. */
        public boolean isIndeterminate() {
            return percent == INDETERMINATE;
        }
    }

    /**
     * Backend requests user input — pauses the stream; terminal writes an {@link Input} frame in
     * reply.
     *
     * @param content the prompt message content to display
     * @param meta prompt-related metadata options (such as masking input characters)
     */
    record Prompt(@NotNull String content, @NotNull Map<String, Object> meta)
            implements ServerFrame {}

    /**
     * Sent by the server to forcefully terminate the terminal connection session.
     *
     * @param reason the reason for termination
     */
    record Terminate(@Nullable String reason) implements TerminalResponse {}

    // ══════════════════════════════════════════════════════════════════════
    //  Fallback
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fallback for unrecognized frame types — used when the remote peer speaks a newer protocol
     * version with frame types this side does not understand. Carries the raw {@code type}
     * discriminator and any deserialized fields so handlers can log-and-skip gracefully instead of
     * crashing on deserialization.
     */
    record Unknown(@NotNull String type, @NotNull Map<String, Object> fields) implements IpcFrame {}
}
