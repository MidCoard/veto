package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.screening.Relevance;
import top.focess.veto.api.plugin.Scope;

/**
 * Transport-neutral application protocol shared by terminal, browser and backend. Every JSON frame
 * has one type discriminator, regardless of its socket transport.
 *
 * <p>Direction and exchange role are independent: a ServerFrame can be a SeqRequest, and a
 * ClientFrame can be its SeqResponse. Transport APIs enforce direction; correlation uses role.
 *
 * <h3>Thread safety</h3>
 *
 * Nested value records and enums are immutable and thread-safe except for payload-bearing {@link
 * Veto} and {@link PluginAction}; {@link Delta} has its own data-dependent contract. CompleteResult
 * and Action copy their lists, whose elements are immutable. A record's final references alone do
 * not make an arbitrary map or JSON tree immutable.
 *
 * <p>Veto args, PluginAction data and Delta.ToolCall args are retained and exposed by reference.
 * Finish constructing those payloads before safe publication, including enqueueing, and treat the
 * entire reachable payload as read-only afterward. None of these types synchronizes mutable payload
 * access or creates a deep snapshot for asynchronous encoding.
 *
 * <h3>Request/response correlation</h3>
 *
 * Either peer may initiate an exchange. Requests carry a sequence allocated by their sender;
 * responses echo that sequence. Receiving a request never completes a locally pending request.
 * Endpoints decide which application operations they accept, independently of these wire types.
 *
 * <p>Request admission and settlement are separate: RequestAccepted confirms admission, while
 * Done/Error echoes the same request sequence after settlement. Session broadcasts use sequence
 * zero and cannot complete a connection-local request. Prompt/Input echo the owning request.
 *
 * <p>Discriminated by the {@code type} field in JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Frame.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = Frame.Request.class, name = "request"),
    @JsonSubTypes.Type(value = Frame.RequestAccepted.class, name = "request_accepted"),
    @JsonSubTypes.Type(value = Frame.DeclineVetoes.class, name = "decline_vetoes"),
    @JsonSubTypes.Type(value = Frame.Complete.class, name = "complete"),
    @JsonSubTypes.Type(value = Frame.Hint.class, name = "hint"),
    @JsonSubTypes.Type(value = Frame.Input.class, name = "input"),
    @JsonSubTypes.Type(value = Frame.Cancel.class, name = "cancel"),
    @JsonSubTypes.Type(value = Frame.CancelResult.class, name = "cancel_result"),
    @JsonSubTypes.Type(value = Frame.Bye.class, name = "bye"),
    @JsonSubTypes.Type(value = Frame.Heartbeat.class, name = "heartbeat"),
    @JsonSubTypes.Type(value = Frame.Welcome.class, name = "welcome"),
    @JsonSubTypes.Type(value = Frame.CompleteResult.class, name = "complete_result"),
    @JsonSubTypes.Type(value = Frame.HintResult.class, name = "hint_result"),
    @JsonSubTypes.Type(value = Frame.Done.class, name = "done"),
    @JsonSubTypes.Type(value = Frame.Error.class, name = "error"),
    @JsonSubTypes.Type(value = Delta.class, name = "delta"),
    @JsonSubTypes.Type(value = Frame.Action.class, name = "action"),
    @JsonSubTypes.Type(value = Frame.PluginAction.class, name = "plugin_action"),
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
                Frame.ScopedFrame {

    /** Frames sent by a client: both client requests and replies to server requests. */
    sealed interface ClientFrame extends Frame
            permits Hello,
                    Request,
                    DeclineVetoes,
                    Complete,
                    Hint,
                    Input,
                    Cancel,
                    Bye,
                    Heartbeat,
                    VetoResult {}

    /** Frames sent by the backend: both server requests and replies to client requests. */
    sealed interface ServerFrame extends Frame
            permits Welcome,
                    RequestAccepted,
                    CompleteResult,
                    HintResult,
                    CancelResult,
                    TurnOutcome,
                    Terminate,
                    Prompt,
                    Veto,
                    Delta,
                    Action,
                    PluginAction,
                    HeartbeatAck {}

    /**
     * Current protocol baseline. Advance only for a major protocol revision, not each frame change.
     */
    int PROTOCOL_VERSION = 5;

    /** A request initiated by either peer. */
    sealed interface SeqRequest extends Frame
            permits Hello, Request, DeclineVetoes, Complete, Hint, Cancel, Prompt, Veto {
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
            implements ClientFrame, SeqRequest {
        public Hello {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

    /** An explicitly correlated command or prompt; browser requests identify their session. */
    record Request(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            UUID sessionId,
            String agentId,
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw)
            implements ClientFrame, SeqRequest {
        public Request {
            if (seq <= 0) throw new IllegalArgumentException("Request sequence must be positive");
            Objects.requireNonNull(raw, "raw");
        }
    }

    /** Admission acknowledgement; never substitutes for the operation's final Done/Error. */
    record RequestAccepted(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Scope scope,
            String requestId)
            implements ServerFrame, SeqResponse, ScopedFrame {
        public RequestAccepted {
            if (seq <= 0) throw new IllegalArgumentException("Request sequence must be positive");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** Refuses pending approvals across one owned session; acknowledged with Done or Error. */
    record DeclineVetoes(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "sessionId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull UUID sessionId)
            implements ClientFrame, SeqRequest {
        public DeclineVetoes {
            if (seq <= 0) throw new IllegalArgumentException("Request sequence must be positive");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

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
            implements ClientFrame, SeqRequest {
        public Complete {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

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
            implements ClientFrame, SeqRequest {
        public Hint {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

    /** User replied to the Prompt belonging to the echoed request sequence. */
    record Input(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame, SeqResponse {
        public Input {
            if (seq <= 0) throw new IllegalArgumentException("Request sequence must be positive");
        }

        @Override
        @NonNull
        public String toString() {
            return "Input[raw=********]";
        }
    }

    /**
     * Requests cancellation of the exact operation identified by targetSeq; acknowledged by
     * CancelResult.
     */
    record Cancel(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "targetSeq", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    long targetSeq)
            implements ClientFrame, SeqRequest {
        public Cancel {
            if (seq <= 0 || targetSeq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

    /**
     * Acknowledges cancellation admission, not command completion. Done/Error still settle the
     * command after its worker exits.
     */
    record CancelResult(
            @JsonProperty(value = "status", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull CancelStatus status,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqResponse {
        public CancelResult {
            Objects.requireNonNull(status, "status");
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

    /** Result of attempting cancellation, independent of command completion. */
    enum CancelStatus {
        /**
         * The interaction was cancelled or worker interruption was requested (possibly earlier).
         */
        ACCEPTED,
        /** The cancellation operation could not be carried out. */
        REJECTED,
        /** The target operation is no longer active on this connection. */
        NO_ACTIVE_REQUEST
    }

    /** Terminal is shutting down cleanly — backend should release resources. Fire-and-forget. */
    record Bye() implements ClientFrame {}

    /** Periodic keep-alive; only one acknowledgement is outstanding per connection. */
    record Heartbeat() implements ClientFrame {}

    /** Application output with an explicit scope; connection-local output uses GlobalScope. */
    sealed interface ScopedFrame extends Frame
            permits Delta, Action, PluginAction, RequestAccepted, TurnOutcome, Veto, VetoResult {
        @NonNull Scope scope();

        @JsonIgnore
        default UUID sessionId() {
            var scope = scope();
            var session = scope.session();
            return session == null ? null : UUID.fromString(session);
        }

        @JsonIgnore
        default String agentId() {
            var scope = scope();
            return scope.agent();
        }
    }

    /** A response to a request initiated by either peer. */
    sealed interface SeqResponse extends Frame
            permits Welcome,
                    RequestAccepted,
                    CompleteResult,
                    HintResult,
                    CancelResult,
                    Done,
                    Error,
                    Input,
                    VetoResult {
        /**
         * Returns the sequence number echoed from the initiating sequenced request.
         *
         * @return the request sequence, or zero for a session-wide observation
         */
        long seq();
    }

    /** Ends an agent turn or command; -1 identifies an outcome without an agent turn. */
    sealed interface TurnOutcome extends ServerFrame, ScopedFrame permits Done, Error {
        int turnNumber();

        /**
         * Current login name in terminal command results; null when signed out or not applicable.
         */
        String username();
    }

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
            implements ServerFrame, SeqResponse {
        public Welcome {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

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
            @JsonProperty(value = "candidates", required = true)
                    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
                    @NonNull List<@NonNull Completion> candidates,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqResponse {
        public CompleteResult {
            candidates = List.copyOf(candidates);
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

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
            implements ServerFrame, SeqResponse {
        public HintResult {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

    /** Completes a command or agent turn. Scope identifies its owner, not terminal login state. */
    record Done(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Scope scope,
            String requestId,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber,
            String content,
            String username,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements TurnOutcome, SeqResponse {
        public Done {
            Objects.requireNonNull(scope, "scope");
            if (seq < 0) throw new IllegalArgumentException("Sequence cannot be negative");
        }
    }

    /**
     * Fatal — terminates the exchange with an error message.
     *
     * <p>Echoes the initiating operation sequence; zero identifies a session-wide observation.
     *
     * @param content error description
     * @param seq echoed from the initiating frame; 0 when not correlated to a sequenced request
     */
    record Error(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Scope scope,
            String requestId,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber,
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            String username,
            String code)
            implements SeqResponse, TurnOutcome {
        public Error {
            Objects.requireNonNull(scope, "scope");
            if (seq < 0) throw new IllegalArgumentException("Sequence cannot be negative");
        }

        public Error(@NonNull String content, long seq) {
            this(new Scope.GlobalScope(), null, -1, content, seq, null, null);
        }

        /**
         * Creates a session-wide observation error ({@code seq = 0}).
         *
         * @param content error description
         * @return the error frame
         */
        public static @NonNull Error ofError(@NonNull String content) {
            return new Error(content, 0);
        }
    }

    /** Terminal-only text input; the client answers with Input. */
    record Prompt(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "mask", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean mask,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqRequest {
        public Prompt {
            if (seq <= 0) throw new IllegalArgumentException("Request sequence must be positive");
        }
    }

    /**
     * Approval request shared by browser and terminal; both clients use the actual agent scope.
     *
     * <p>Not unconditionally thread-safe: options are copied, but args and its nested values are
     * retained without copying or synchronization. Keep them read-only after publication.
     */
    record Veto(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    Scope.@NonNull AgentScope scope,
            @JsonProperty(value = "callId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String callId,
            @JsonProperty(value = "tool", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String tool,
            @JsonProperty(value = "scenario", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String scenario,
            @JsonProperty(value = "options", required = true)
                    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
                    @NonNull List<@NonNull String> options,
            @JsonProperty(value = "args", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Map<String, Object> args,
            Danger danger,
            Relevance relevance,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqRequest, ScopedFrame {
        // Explicit parameters preserve nullable screening levels across Checker module boundaries.
        public Veto(
                Scope.@NonNull AgentScope scope,
                @NonNull String callId,
                @NonNull String tool,
                @NonNull String scenario,
                @NonNull List<@NonNull String> options,
                @NonNull Map<String, Object> args,
                Danger danger,
                Relevance relevance,
                long seq) {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
            this.scope = scope;
            this.callId = callId;
            this.tool = tool;
            this.scenario = scenario;
            this.options = List.copyOf(options);
            this.args = args;
            this.danger = danger;
            this.relevance = relevance;
            this.seq = seq;
        }
    }

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
            implements ClientFrame, SeqResponse, ScopedFrame {
        public VetoResult {
            if (seq <= 0)
                throw new IllegalArgumentException("Sequence must be positive; 0 is uncorrelated");
        }
    }

    /** Core resource refresh hints, independent of conversation turns and Done. */
    record Action(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    Scope.@NonNull SessionScope scope,
            @JsonProperty(value = "resources", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull List<@NonNull String> resources)
            implements ServerFrame, ScopedFrame {
        public Action {
            resources = List.copyOf(resources);
            if (resources.isEmpty())
                throw new IllegalArgumentException("An action needs resources");
        }
    }

    /**
     * Independent plugin publication. Does not complete a command or require a Done frame.
     *
     * <p>Not unconditionally thread-safe: the mutable data tree is retained and exposed without
     * copying or synchronization. Keep the entire tree read-only after publication.
     */
    record PluginAction(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    Scope.@NonNull SessionScope scope,
            @JsonProperty(value = "topic", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String topic,
            @JsonProperty(value = "data", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull ObjectNode data)
            implements ServerFrame, ScopedFrame {}

    /**
     * Sent by the server to forcefully terminate the terminal connection session.
     *
     * @param reason the reason for termination
     */
    record Terminate(String reason) implements ServerFrame {}

    /** Acknowledges the outstanding heartbeat on this connection. */
    record HeartbeatAck(
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}
}
