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
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Transport-neutral application protocol shared by terminal, browser and backend. Every JSON frame
 * has one type discriminator, regardless of its socket transport.
 *
 * <p>Direction and exchange role are independent: a ServerFrame can be a SeqRequest, and a
 * ClientFrame can be its SeqResponse. Transport APIs enforce direction; correlation uses role.
 *
 * <h3>Request/response correlation</h3>
 *
 * Either peer may initiate an exchange. Requests carry a sequence allocated by their sender;
 * responses echo that sequence. Receiving a request never completes a locally pending request.
 * Endpoints decide which application operations they accept, independently of these wire types.
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
    @JsonSubTypes.Type(value = Delta.class, name = "delta"),
    @JsonSubTypes.Type(value = Frame.Prompt.class, name = "prompt"),
    @JsonSubTypes.Type(value = Frame.Veto.class, name = "veto"),
    @JsonSubTypes.Type(value = Frame.VetoResult.class, name = "veto_result"),
    @JsonSubTypes.Type(value = Frame.Terminate.class, name = "terminate"),
    @JsonSubTypes.Type(value = Frame.HeartbeatAck.class, name = "heartbeat_ack")
})
public sealed interface Frame
        permits Frame.ClientFrame,
                Frame.ServerFrame,
                Frame.SeqRequest,
                Frame.SeqResponse,
                Frame.ScopedFrame,
                Frame.Interaction {

    /** Frames sent by a client: both client requests and replies to server requests. */
    sealed interface ClientFrame extends Frame
            permits Hello, Request, Complete, Hint, Input, Cancel, Bye, Heartbeat, VetoResult {}

    /** Frames sent by the backend: both server requests and replies to client requests. */
    sealed interface ServerFrame extends Frame
            permits Welcome,
                    CompleteResult,
                    HintResult,
                    TerminalResponse,
                    Prompt,
                    Veto,
                    Delta,
                    HeartbeatAck {}

    /** Current protocol version. Increment when frame types or semantics change incompatibly. */
    int PROTOCOL_VERSION = 7;

    /** A request initiated by either peer. */
    sealed interface SeqRequest extends Frame permits Hello, Complete, Hint, Veto {
        /**
         * Returns the sequence number associated with this request.
         *
         * @return the sequence number
         */
        long seq();
    }

    /**
     * Protocol negotiation, required before application traffic on every connection. The backend
     * responds with a {@link Welcome} echoing the same {@code seq}.
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
            implements ClientFrame, SeqRequest {}

    /**
     * User typed a command or plain-text prompt.
     *
     * <p>The response is <b>indeterminate</b> — the backend may emit zero or more {@link Delta},
     * {@link Prompt} frames before a terminal {@link Done} or {@link Error}. Because there is no
     * single determined response, this frame does <b>not</b> carry a {@code seq}.
     */
    record Request(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw)
            implements ClientFrame {}

    /**
     * Tab-completion query. Backend responds with exactly one {@link CompleteResult} carrying
     * structured completion candidates, echoing the same {@code seq}.
     *
     * @param seq monotonic sequence number for correlating with the response
     */
    record Complete(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame, SeqRequest {}

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
            implements ClientFrame, SeqRequest {}

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

    /** Periodic keep-alive; only one acknowledgement is outstanding per connection. */
    record Heartbeat() implements ClientFrame {}

    /** Application output whose scope is absent only for connection-local output. */
    sealed interface ScopedFrame extends Frame permits Delta, Done, Error, Veto, VetoResult {
        Scope scope();

        @JsonIgnore
        default UUID sessionId() {
            var scope = scope();
            var session = scope == null ? null : scope.session();
            return session == null ? null : UUID.fromString(session);
        }

        @JsonIgnore
        default String agentId() {
            var scope = scope();
            return scope == null ? null : scope.agent();
        }
    }

    /** A response to a request initiated by either peer. */
    sealed interface SeqResponse extends Frame
            permits Welcome, CompleteResult, HintResult, Error, VetoResult {
        /**
         * Returns the sequence number echoed from the initiating sequenced request.
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
            implements ServerFrame, SeqResponse {}

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
            implements ServerFrame, SeqResponse {}

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
            implements ServerFrame, SeqResponse {}

    /** Completes a command or agent turn. Scope identifies its owner, not terminal login state. */
    record Done(
            Scope scope,
            String requestId,
            Integer turnNumber,
            @JsonProperty(value = "cancelled", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean cancelled,
            String content)
            implements TerminalResponse, ScopedFrame {
        public Done(String content) {
            this(null, null, null, false, content);
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
            Scope scope,
            String requestId,
            Integer turnNumber,
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements SeqResponse, TerminalResponse, ScopedFrame {
        public Error(@NonNull String content, long seq) {
            this(null, null, null, content, seq);
        }

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

    /** A user interaction rendered by the terminal: text input or an approval picker. */
    sealed interface Interaction extends Frame permits Prompt, Veto {}

    /** Terminal-only text input; the client answers with Input. */
    record Prompt(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "mask", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean mask)
            implements ServerFrame, Interaction {}

    /** Approval request shared by browser and terminal; both clients use the actual agent scope. */
    record Veto(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    Scope.@NonNull AgentScope scope,
            @JsonProperty(value = "callId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String callId,
            @JsonProperty(value = "tool", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String tool,
            @JsonProperty(value = "scenario", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String scenario,
            @JsonProperty(value = "options", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull List<String> options,
            @JsonProperty(value = "args", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Map<String, Object> args,
            String danger,
            String relevance,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqRequest, Interaction, ScopedFrame {}

    /**
     * Reply to a Veto request. Echoes its sequence; execution or refusal is reported through
     * tool-result deltas.
     */
    record VetoResult(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    Scope.@NonNull AgentScope scope,
            @JsonProperty(value = "callId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String callId,
            @JsonProperty(value = "option", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String option,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame, SeqResponse, ScopedFrame {}

    /**
     * Sent by the server to forcefully terminate the terminal connection session.
     *
     * @param reason the reason for termination
     */
    record Terminate(String reason) implements TerminalResponse {}

    /** Acknowledges the outstanding heartbeat on this connection. */
    record HeartbeatAck(
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}
}
