package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Transport-neutral application protocol shared by terminal, browser and backend. Every JSON frame
 * has one type discriminator, regardless of its socket transport.
 *
 * <h3>Directional safety</h3>
 *
 * <ul>
 *   <li>{@link ClientFrame} — frames sent <b>from a client to the backend</b>.
 *   <li>{@link ServerFrame} — frames sent <b>from the backend to a client</b>.
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
 * {@link Bye}, {@link Input}) also omit it.
 *
 * <p>Discriminated by the {@code type} field in JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Frame.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = Frame.Request.class, name = "request"),
    @JsonSubTypes.Type(value = Frame.Complete.class, name = "complete"),
    @JsonSubTypes.Type(value = Frame.Hint.class, name = "hint"),
    @JsonSubTypes.Type(value = Frame.Input.class, name = "input"),
    @JsonSubTypes.Type(value = Frame.Cancel.class, name = "cancel"),
    @JsonSubTypes.Type(value = Frame.Bye.class, name = "bye"),
    @JsonSubTypes.Type(value = Frame.Heartbeat.class, name = "heartbeat"),
    @JsonSubTypes.Type(value = Frame.Welcome.class, name = "welcome"),
    @JsonSubTypes.Type(value = Frame.CompleteResult.class, name = "complete_result"),
    @JsonSubTypes.Type(value = Frame.HintResult.class, name = "hint_result"),
    @JsonSubTypes.Type(value = Frame.Done.class, name = "done"),
    @JsonSubTypes.Type(value = Frame.Error.class, name = "error"),
    @JsonSubTypes.Type(value = EventFrame.class, name = "event"),
    @JsonSubTypes.Type(value = Frame.Progress.class, name = "progress"),
    @JsonSubTypes.Type(value = Frame.Prompt.class, name = "prompt"),
    @JsonSubTypes.Type(value = Frame.Terminate.class, name = "terminate"),
    @JsonSubTypes.Type(value = Frame.HeartbeatAck.class, name = "heartbeat_ack"),
    @JsonSubTypes.Type(value = Frame.Subscribe.class, name = "subscribe"),
    @JsonSubTypes.Type(value = Frame.Unsubscribe.class, name = "unsubscribe"),
    @JsonSubTypes.Type(value = Frame.Process.class, name = "veto.process"),
    @JsonSubTypes.Type(value = Frame.Received.class, name = "dag.received"),
    @JsonSubTypes.Type(value = Frame.DagPayload.class, name = "dag.payload"),
    @JsonSubTypes.Type(value = Frame.VetoResult.class, name = "veto.result"),
    @JsonSubTypes.Type(value = Frame.Subscribed.class, name = "subscribed"),
    @JsonSubTypes.Type(value = Frame.Unsubscribed.class, name = "unsubscribed")
})
public sealed interface Frame permits Frame.ClientFrame, Frame.ServerFrame {

    /** Current protocol version. Increment when frame types or semantics change incompatibly. */
    int PROTOCOL_VERSION = 2;

    // ══════════════════════════════════════════════════════════════════════
    // Client → Server
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Frames sent from a client to the backend.
     *
     * <p>Only frames that expect a <b>determined single response</b> carry a {@code seq}: these
     * implement {@link SeqRequest}. All others are fire-and-forget or have indeterminate output.
     */
    sealed interface ClientFrame extends Frame
            permits SeqRequest,
                    Request,
                    Input,
                    Cancel,
                    Bye,
                    Heartbeat,
                    Subscribe,
                    Unsubscribe,
                    Process,
                    DagPayload {}

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
     * Optional protocol negotiation, required by the terminal on connect. The backend responds with
     * a {@link Welcome} echoing the same {@code seq}.
     *
     * <p>Authentication belongs to the application. Browser WebSockets require a valid login token
     * at the HTTP upgrade and on each frame; terminal account commands authenticate the local
     * connection. A successful Hello never grants account access.
     *
     * @param version the exact protocol version the client uses
     * @param seq monotonic sequence number for correlating with the {@link Welcome} response
     * @param productVersion the connecting client's product version; never {@code null} - a client
     *     that genuinely cannot report a version passes {@link Version#UNKNOWN}. The server echoes
     *     its own in {@link Welcome}.
     * @param cwd the connecting terminal's current working directory, mapped to the session's
     *     workspace at {@code /session create} time; never {@code null} - the terminal always
     *     reports its JVM working dir. The browser supplies workspace roots through REST and does
     *     not use this field.
     */
    record Hello(
            @JsonProperty(value = "version", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int version,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "productVersion", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Version productVersion,
            @JsonProperty(value = "cwd", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String cwd)
            implements SeqRequest {}

    /**
     * User typed a command or plain-text prompt.
     *
     * <p>The response is <b>indeterminate</b> — the backend may emit zero or more {@link
     * EventFrame}, {@link Prompt}, {@link Progress} frames before a terminal {@link Done} or {@link
     * Error}. Because there is no single determined response, this frame does <b>not</b> carry a
     * {@code seq}.
     */
    record Request(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw)
            implements ClientFrame {}

    /**
     * Tab-completion query. Backend responds with exactly one {@link CompleteResult} carrying
     * newline-separated suggestions, echoing the same {@code seq}.
     *
     * @param seq monotonic sequence number for correlating with the response
     */
    record Complete(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements SeqRequest {}

    /**
     * Requests a placeholder hint for the next expected argument. The backend responds with exactly
     * one {@link HintResult} carrying the placeholder and optional description, echoing the same
     * {@code seq}.
     *
     * @param seq monotonic sequence number for correlating with the hint response
     */
    record Hint(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements SeqRequest {}

    /** User replied to a backend-issued {@link Prompt}. Fire-and-forget. */
    record Input(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw)
            implements ClientFrame {
        @Override
        @NonNull
        public String toString() {
            return "Input[raw=********]";
        }
    }

    /** User interrupted the current request (Ctrl+C). Fire-and-forget. */
    record Cancel() implements ClientFrame {}

    /** Terminal is shutting down cleanly — backend should release resources. Fire-and-forget. */
    record Bye() implements ClientFrame {}

    /** Periodic keep-alive; the server echoes its sequence in HeartbeatAck. */
    record Heartbeat(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame {}

    // ══════════════════════════════════════════════════════════════════════
    // Server → Client
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Frames sent from the backend to a client.
     *
     * <p>Only frames that are <b>direct responses</b> to a sequenced client frame carry a {@code
     * seq}: these implement {@link SeqResponse}. Streaming frames ({@link EventFrame}, {@link
     * Progress}, {@link Prompt}, {@link Done}) do not carry a {@code seq}.
     */
    sealed interface ServerFrame extends Frame
            permits SeqResponse,
                    TerminalResponse,
                    EventFrame,
                    Progress,
                    Prompt,
                    HeartbeatAck,
                    Received,
                    DagPayload,
                    VetoResult,
                    Subscribed,
                    Unsubscribed {}

    /** Marker interface for all seq-based server response frames. */
    sealed interface SeqResponse extends ServerFrame
            permits Welcome, CompleteResult, HintResult, Error {
        /**
         * Returns the sequence number echoed from the initiating sequenced client request.
         *
         * @return the sequence number; {@code 0} when not correlated to a sequenced request (e.g.
         *     an {@link Error} replying to a {@link Request})
         */
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
     * @param productVersion the server's product version; never {@code null}
     */
    record Welcome(
            @JsonProperty(value = "version", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int version,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "productVersion", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Version productVersion)
            implements SeqResponse {}

    /**
     * An autocomplete candidate returned in a {@link CompleteResult}.
     *
     * @param value the completion suggestion text (e.g. {@code /login})
     * @param description optional helpful description or context
     * @param group optional category/group name for JLine rendering grouping
     */
    record Completion(
            @JsonProperty(value = "value", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String value,
            String description,
            String group) {}

    /**
     * Response containing autocomplete candidates.
     *
     * @param candidates structured list of completion candidates
     * @param seq echoed from the initiating {@link Complete} request
     */
    record CompleteResult(
            @JsonProperty(value = "candidates", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull List<Completion> candidates,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements SeqResponse {}

    /**
     * Information about the autocomplete hint for the next expected argument.
     *
     * @param placeholder the template or placeholder representing the expected argument
     * @param description a helpful description explaining the argument
     */
    record HintInfo(String placeholder, String description) {
        /** Empty placeholder instance. */
        public static final @NonNull HintInfo EMPTY = new HintInfo(null, null);

        /**
         * Returns the display text to render.
         *
         * @return the formatted hint display text
         */
        @JsonIgnore
        public @NonNull String displayText() {
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
    record HintResult(
            @JsonProperty(value = "hint", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull HintInfo hint,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements SeqResponse {}

    /**
     * Terminal frame — response complete.
     *
     * <p>Done is at the end of a sequence of event and progress frames. It does not carry a
     * sequence number.
     *
     * @param meta session metadata (username, turn number, flags, etc.)
     * @param content optional content string
     */
    record Done(
            @JsonProperty(value = "meta", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Map<String, Object> meta,
            String content)
            implements TerminalResponse {
        /** Typed, null-safe accessor for {@link FrameMeta#USERNAME}. */
        public String username() {
            return FrameMeta.username(meta);
        }

        /** Typed accessor for {@link FrameMeta#TURN_NUMBER}; {@code -1} when absent. */
        public int turnNumber() {
            return FrameMeta.turnNumber(meta, -1);
        }

        /** Typed accessor for {@link FrameMeta#CANCELLED}. */
        public boolean cancelled() {
            return FrameMeta.cancelled(meta);
        }

        /** Typed accessor for {@link FrameMeta#CLEAR_SESSION}. */
        public boolean clearSession() {
            return FrameMeta.clearSession(meta);
        }
    }

    /**
     * Fatal — terminates the exchange with an error message.
     *
     * <p>Echoes the {@code seq} from the initiating frame. When emitted in response to a {@link
     * Request} (which has no seq), use {@code seq = 0}.
     *
     * @param content error description
     * @param seq echoed from the initiating frame; 0 when not correlated to a sequenced request
     */
    record Error(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements SeqResponse, TerminalResponse {
        /**
         * Creates an uncorrelated error ({@code seq = 0}), e.g. a reply to a {@link Request} which
         * carries no sequence number.
         *
         * @param content error description
         * @return the error frame
         */
        public static @NonNull Error ofError(@NonNull String content) {
            return new Error(content, 0);
        }
    }

    /**
     * Optional progress hint between deltas.
     *
     * @param percent completion percentage 0–100, or {@link #INDETERMINATE} when unknown
     */
    record Progress(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "percent", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int percent)
            implements ServerFrame {
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
     * <p>Two shapes share one frame type:
     *
     * <ul>
     *   <li><b>Free-text prompt</b> ({@code veto == null}) - the terminal renders {@code content}
     *       as an input field (optionally masked) and replies with the typed line.
     *   <li><b>HITL veto</b> ({@code veto != null}) - the terminal renders a picker from {@link
     *       VetoPayload#options()} and replies with the chosen option name as the {@link
     *       Input#raw()} string. Parking stays server-side (the agent parks in the HitlRegistry);
     *       the reply is correlated by the 1:1 request invariant (at most one veto pending per
     *       session at a time), so the {@code Input} carries only the option name.
     * </ul>
     *
     * @param content the prompt message content to display
     * @param mask whether to mask the user's input characters (e.g. for password fields); always
     *     {@code false} for a veto picker
     * @param veto the optional HITL veto payload; {@code null} for a free-text prompt
     */
    record Prompt(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "mask", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean mask,
            VetoPayload veto)
            implements ServerFrame {
        /** Free-text prompt constructor (no veto payload) - keeps existing callers unchanged. */
        public Prompt(@NonNull String content, boolean mask) {
            this(content, mask, null);
        }
    }

    /**
     * The HITL veto payload carried by a {@link Prompt} - everything the terminal needs to render a
     * picker and reply with a chosen option, in transport-pure types (no core dependency). The
     * reply is an {@link Input} whose {@code raw} is the chosen option name (one of {@link
     * #options()}).
     *
     * @param agentId the agent (persona) id the veto is parked under - the HitlRegistry key
     * @param callId the tool-call id the veto is parked under
     * @param tool the tool name being approved/refused
     * @param scenario the {@code VetoScenario} name (display + grouping)
     * @param options the offered option names (VetoOption names); the reply must be one of these
     * @param args the call's arguments (display-only - the user approves the actual call)
     */
    record VetoPayload(
            @JsonProperty(value = "agentId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String agentId,
            @JsonProperty(value = "callId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String callId,
            @JsonProperty(value = "tool", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String tool,
            @JsonProperty(value = "scenario", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String scenario,
            @JsonProperty(value = "options", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull List<String> options,
            @JsonProperty(value = "args", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Map<String, Object> args) {}

    /**
     * Sent by the server to forcefully terminate the terminal connection session.
     *
     * @param reason the reason for termination
     */
    record Terminate(String reason) implements TerminalResponse {}

    /** Acknowledges a heartbeat using the same correlation value on every transport. */
    record HeartbeatAck(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}

    /** Selects a WebSocket delivery topic; omitted topic selects all. */
    record Subscribe(String topic) implements ClientFrame {}

    /** Removes the connection's topic selection. */
    record Unsubscribe() implements ClientFrame {}

    /** Requests gateway processing; routing defaults are chosen by the application. */
    record Process(
            @JsonProperty(value = "payload", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String payload,
            String dagPayloadId,
            String requestId,
            String componentSource)
            implements ClientFrame {}

    /** Confirms receipt of a DAG payload. */
    record Received(
            @JsonProperty(value = "taskType", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String taskType,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}

    /** Shared structured DAG data, sent by a client or forwarded by the server. */
    record DagPayload(
            @JsonProperty(value = "data", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull DAGPayload data,
            String source)
            implements ClientFrame, ServerFrame {}

    /** Gateway processing outcome. */
    record VetoResult(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "decision", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String decision,
            @JsonProperty(value = "processedPayload", required = true)
                    @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String processedPayload,
            @JsonProperty(value = "reason", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String reason,
            @JsonProperty(value = "redactionCount", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int redactionCount,
            @JsonProperty(value = "allowed", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean allowed,
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}

    /** Confirms a delivery topic. */
    record Subscribed(
            @JsonProperty(value = "topic", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String topic,
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}

    /** Confirms removal of the delivery topic. */
    record Unsubscribed(
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}
}
