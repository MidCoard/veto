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
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.screening.Relevance;
import top.focess.veto.api.plugin.Scope;

/**
 * Application protocol shared by the terminal, browser and backend. Record Javadoc defines each
 * payload's fields; this contract defines how the frames participate in an exchange.
 *
 * <h2>Representation, direction and identity</h2>
 *
 * Each frame is one JSON object with a {@code type} discriminator. Both current frontends carry
 * this object in a WebSocket text message; socket packets and WebSocket fragments are not separate
 * application frames. {@link FrameCodec#MAX_ENCODED_FRAME_BYTES} limits the complete UTF-8 JSON
 * representation, excluding transport headers. It is not a packet size or a queue-capacity limit;
 * the codec does not split oversized payloads. A successful local send does not prove server
 * admission or peer receipt.
 *
 * <p>{@link ClientFrame} and {@link ServerFrame} specify direction. {@link SeqRequest} and {@link
 * SeqResponse} specify exchange role: a server can ask for input or approval and a client can
 * reply. A {@link ScopedFrame#scope()} routes a publication or identifies an operation's owner; it
 * does not grant access. The backend must still check the connection's authenticated identity and
 * access to the target session/agent. {@link Hello}/{@link Welcome} negotiate {@link
 * #PROTOCOL_VERSION}; they do not authenticate an account.
 *
 * <p>Client-initiated requests allocate a nonzero {@code seq} once per connection. Responses echo
 * it. The sign has no meaning; zero identifies an uncorrelated observation, never a pending client
 * request. TypeScript peers require integers representable exactly as JavaScript safe integers. Do
 * not reuse a request sequence on that connection, even after completion. A new connection has a
 * new correlation namespace. {@code requestId} is an agent runtime identity, not a replacement for
 * the connection-local sequence; {@code turnNumber} is history metadata, not an exchange ID.
 *
 * <p>Server interactions have different identities: {@link Prompt}/{@link Input} use the owning
 * command's sequence, while {@link Veto}/{@link VetoResult} match the exact agent scope, call ID
 * and server-provided sequence. Receiving either server request does not settle a client request
 * with the same numeric sequence. Frames from different operations may interleave; correlate by
 * type, sequence and the relevant scope, not by arrival adjacency.
 *
 * <h2>Implemented exchanges</h2>
 *
 * <table>
 * <caption>Responses and completion rules on a live, authorized connection</caption>
 * <thead><tr><th scope="col">Initiating frame</th><th scope="col">Reply and effect</th></tr></thead>
 * <tbody>
 * <tr><td>{@link Hello}</td><td>{@link Welcome} echoes its sequence. A rejected handshake returns
 * {@link Error} and closes the connection. Application traffic waits for Welcome.</td></tr>
 * <tr><td>{@link Request}</td><td>{@link RequestAccepted} confirms admission, not execution success.
 * One correlated {@link Done} or {@link Error} settles normal execution; successful cancellation
 * instead settles through CancelResult. Rejection before admission
 * can return Error without RequestAccepted. A command that ends the connection can instead emit
 * {@link Terminate}, without a correlated completion frame.</td></tr>
 * <tr><td>{@link DeclineVetoes}</td><td>Done/Error acknowledges this approval-refusal operation
 * using its own sequence. It does not cancel an agent request.</td></tr>
 * <tr><td>{@link Complete} / {@link Hint}</td><td>{@link CompleteResult} / {@link HintResult}, or
 * Error, echoes the query sequence. No additional Done follows.</td></tr>
 * <tr><td>{@link Prompt}</td><td>Input answers the command's current text prompt. The command stays
 * active and later produces its own outcome; Input has no separate success acknowledgement.</td></tr>
 * <tr><td>{@link Veto}</td><td>VetoResult chooses an offered option for that exact approval.
 * Subsequent tool/interaction publications describe the effect; this is not task cancellation.</td></tr>
 * <tr><td>{@link Cancel}</td><td>{@link CancelResult} uses the Cancel sequence. CANCELLED confirms
 * actual target settlement and replaces its correlated Done/Error. REJECTED leaves the target's
 * normal completion path intact; NO_ACTIVE_REQUEST identifies an absent/completed target.</td></tr>
 * <tr><td>{@link Heartbeat}</td><td>{@link HeartbeatAck} creates receive activity without a sequence
 * or a pending heartbeat exchange.</td></tr>
 * <tr><td>{@link Bye} / {@link Terminate}</td><td>Closes the connection without a protocol reply.
 * This is not a successful cancellation result for outstanding agent work.</td></tr>
 * <tr><td>{@link Delta}, {@link Action}, {@link PluginAction}</td><td>Independent publications:
 * no reply or trailing Done is required. Action requests a core-resource refresh; PluginAction
 * carries plugin-owned data; Delta describes scoped agent/session activity.</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Settlement means the operation's host execution and required cleanup have finished. Request
 * admission, an interrupt request, a completed result future, or a UI status change alone does not
 * establish settlement. A correlated outcome settles only its original operation; it does not close
 * the frontend or socket. Done/Error with {@code seq = 0} is a session observation and cannot
 * release a client's pending command. On disconnect an unresolved exchange is unconfirmed: do not
 * report success or automatically replay a prompt. Sequence reuse or an invalid handshake can close
 * the connection; not every protocol error has an independently correlatable reply.
 *
 * <h2 id="cancellation">Final cancellation result</h2>
 *
 * Cancellation has one final result, rather than an admission acknowledgement followed by the
 * target's Done/Error. A request to stop is not proof that execution stopped.
 *
 * <ol>
 *   <li>{@code Cancel(cancelSeq, targetSeq)} identifies one outstanding operation on this
 *       authorized connection. It does not terminate the connection, frontend or unrelated work.
 *   <li>On successful cancellation, wait for the target's execution and cleanup to settle, then
 *       return {@code CancelResult(CANCELLED, cancelSeq)}. This result settles the Cancel exchange
 *       and its target. Do not also send correlated Done/Error for that target. The client retains
 *       the cancelSeq-to-targetSeq association until the result arrives.
 *   <li>REJECTED settles only the cancellation attempt; the original operation retains its normal
 *       completion path. NO_ACTIVE_REQUEST means there is no outstanding target. If normal
 *       completion wins the race, keep its Done/Error and report NO_ACTIVE_REQUEST to Cancel; never
 *       manufacture another target outcome or claim that completed work was cancelled.
 *   <li>Repeat attempts use distinct cancellation sequences. While cancellation is pending, do not
 *       interrupt cleanup again; answer each admitted attempt after the same settlement. Once the
 *       target is retired, a later attempt returns NO_ACTIVE_REQUEST.
 *   <li>A pending command Prompt is released as part of cancelling its owning operation. Merely
 *       dismissing the prompt must not produce CANCELLED while its command can still execute.
 *       Approval refusal continues to use VetoResult/DeclineVetoes, not this cancellation result.
 *   <li>If execution has not stopped, cancellation remains pending; a timeout or disconnect cannot
 *       turn it into success. Already committed effects are not rolled back, and remote services
 *       may continue work already received. Late results must not trigger new host work after
 *       cancellation settles. Scoped history and resource publications remain independent of
 *       connection-local completion.
 * </ol>
 *
 * <pre>{@code
 * Proposed successful cancellation:
 * client -> server: Request(seq=10, ...)
 * server -> client: RequestAccepted(seq=10, ...)
 * client -> server: Cancel(seq=11, targetSeq=10)
 *                   ... target stops and cleanup settles ...
 * server -> client: CancelResult(status=CANCELLED, seq=11)
 *                   No Done(seq=10) or Error(seq=10).
 * }</pre>
 *
 * <h2>Liveness and independent output</h2>
 *
 * Any valid received frame refreshes that peer's receive activity. A client sends Heartbeat when it
 * has no business data to send; receiving server pushes does not replace client-to-server traffic.
 * There is no separate awaiting-HeartbeatAck state. HeartbeatAck's timestamp is observational, not
 * a deadline. Timing and output-queue bounds belong to the connection/transport configuration, not
 * to a command's sequence or lifetime. Loss of connection is not proof that server work stopped,
 * and a live heartbeat is not proof that a task is making progress.
 *
 * <h2>Thread safety</h2>
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
     * @param seq connection-local identifier for correlating with the {@link Welcome} response
     * @param productVersion the connecting client's product version; never {@code null} - a client
     *     that genuinely cannot report a version passes {@link Version#UNKNOWN}. The server echoes
     *     its own in {@link Welcome}.
     * @param cwd terminal's resolved workspace directory, used when creating a session; defaults to
     *     the process working directory unless a workspace was selected. Never {@code null}. The
     *     terminal validates the directory before connecting. The browser supplies workspace roots
     *     through REST and does not use this field.
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
        /**
         * Creates a handshake request with a correlatable sequence.
         *
         * @param version wire protocol version
         * @param seq nonzero client handshake identifier
         * @param productVersion advertised client build
         * @param cwd advertised terminal working directory
         * @throws IllegalArgumentException if seq is zero
         */
        public Hello {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Starts a command or agent prompt. RequestAccepted confirms admission; Done/Error later
     * settles the same sequence. Browser requests identify a session; terminal commands can use the
     * connection's selected session.
     *
     * <p>A terminal command that ends the connection can return Terminate instead. A closed
     * connection cannot promise delivery of a final correlated outcome.
     *
     * <p>The <a href="Frame.html#cancellation">cancellation contract</a> replaces the normal
     * outcome only when cancellation actually succeeds; a rejected cancellation leaves this
     * request's completion path intact.
     *
     * @param seq nonzero identifier allocated by the requesting client
     * @param sessionId target session, or null for terminal connection selection/global commands
     * @param agentId target agent, or null to use the session's primary agent
     * @param raw command or prompt text to execute
     */
    record Request(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            UUID sessionId,
            String agentId,
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw)
            implements ClientFrame, SeqRequest {
        /**
         * Creates a command or prompt request.
         *
         * @param seq nonzero client request identifier
         * @param sessionId target session, or null for terminal selection/global commands
         * @param agentId target agent, or null for the primary agent
         * @param raw command or prompt text
         * @throws IllegalArgumentException if seq is zero
         */
        public Request {
            if (seq == 0) throw new IllegalArgumentException("Request sequence must be nonzero");
        }
    }

    /**
     * Confirms that a request was admitted. This is not its final Done/Error outcome.
     *
     * @param seq echoed client request sequence
     * @param scope owner of the admitted operation; global for connection-local commands
     * @param requestId agent runtime request identifier, or null for a command without one
     */
    record RequestAccepted(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Scope scope,
            String requestId)
            implements ServerFrame, SeqResponse, ScopedFrame {
        /**
         * Creates an admission acknowledgement without settling the request.
         *
         * @param seq echoed nonzero request sequence
         * @param scope admitted operation's owner
         * @param requestId agent runtime request identifier, or null for an ordinary command
         * @throws IllegalArgumentException if seq is zero
         */
        public RequestAccepted {
            if (seq == 0) throw new IllegalArgumentException("Request sequence must be nonzero");
        }
    }

    /**
     * Refuses pending approvals across one owned session; acknowledged with Done or Error.
     *
     * @param seq nonzero client request identifier for the acknowledgement
     * @param sessionId owned session whose pending tool approvals should be refused
     */
    record DeclineVetoes(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "sessionId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull UUID sessionId)
            implements ClientFrame, SeqRequest {
        /**
         * Creates a session approval-refusal request.
         *
         * @param seq nonzero client request identifier
         * @param sessionId session whose approvals should be refused
         * @throws IllegalArgumentException if seq is zero
         */
        public DeclineVetoes {
            if (seq == 0) throw new IllegalArgumentException("Request sequence must be nonzero");
        }
    }

    /**
     * Tab-completion query. A successful query returns one {@link CompleteResult}; failure may
     * return Error. Both echo the query's sequence.
     *
     * @param raw current command text to complete
     * @param seq connection-local identifier for correlating with the response
     */
    record Complete(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame, SeqRequest {
        /**
         * Creates a completion query.
         *
         * @param raw command text to complete
         * @param seq nonzero client query identifier
         * @throws IllegalArgumentException if seq is zero
         */
        public Complete {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Requests a placeholder hint for the next expected argument. A successful query returns {@link
     * HintResult}; failure may return Error. Both echo the query's sequence.
     *
     * @param raw current command text for which the next argument is requested
     * @param seq connection-local identifier for correlating with the hint response
     */
    record Hint(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame, SeqRequest {
        /**
         * Creates an argument-hint query.
         *
         * @param raw current command text
         * @param seq nonzero client query identifier
         * @throws IllegalArgumentException if seq is zero
         */
        public Hint {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Supplies input to a terminal Prompt. Logging this record masks the text because it may
     * contain a password; the wire payload still contains the actual input.
     *
     * @param raw user input, including sensitive text when the prompt was masked
     * @param seq sequence echoed from Prompt, identifying its owning command
     */
    record Input(
            @JsonProperty(value = "raw", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String raw,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ClientFrame, SeqResponse {
        /**
         * Creates a reply to the command's active text prompt.
         *
         * @param raw user input, possibly sensitive
         * @param seq echoed nonzero Prompt sequence
         * @throws IllegalArgumentException if seq is zero
         */
        public Input {
            if (seq == 0) throw new IllegalArgumentException("Request sequence must be nonzero");
        }

        @Override
        @NonNull
        public String toString() {
            return "Input[raw=********]";
        }
    }

    /**
     * Requests cancellation of the exact operation identified by targetSeq; acknowledged by
     * CancelResult. Cancellation has its own sequence so its response cannot be mistaken for the
     * target command's final outcome.
     *
     * <p>A pending Prompt is released as part of cancelling its owning operation. The response
     * waits for actual execution and cleanup; it does not refuse an unrelated approval or close the
     * connection. See the <a href="Frame.html#cancellation">final-result contract</a>.
     *
     * @param seq nonzero client sequence allocated for this cancellation request
     * @param targetSeq nonzero sequence of the operation to cancel on this connection
     */
    record Cancel(
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq,
            @JsonProperty(value = "targetSeq", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    long targetSeq)
            implements ClientFrame, SeqRequest {
        /**
         * Creates a cancellation request with separate request and target identifiers.
         *
         * @param seq nonzero sequence for this Cancel
         * @param targetSeq nonzero sequence of the operation to cancel
         * @throws IllegalArgumentException if either sequence is zero
         */
        public Cancel {
            if (seq == 0 || targetSeq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Final response to a Cancel. CANCELLED confirms that the target's execution and cleanup have
     * settled and replaces its correlated Done/Error; it does not merely acknowledge an interrupt.
     *
     * <p>The client retains the Cancel's targetSeq association until this result arrives. REJECTED
     * leaves the target active; NO_ACTIVE_REQUEST does not manufacture a target outcome. See the <a
     * href="Frame.html#cancellation">cancellation contract</a> for races and repeated attempts.
     *
     * @param status final cancellation outcome; only CANCELLED confirms target cancellation
     * @param seq echoed Cancel sequence, not the cancelled operation's targetSeq
     */
    record CancelResult(
            @JsonProperty(value = "status", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull CancelStatus status,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqResponse {
        /**
         * Creates the response to a cancellation attempt.
         *
         * @param status final cancellation outcome
         * @param seq echoed nonzero Cancel sequence
         * @throws IllegalArgumentException if seq is zero
         */
        public CancelResult {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /** Final outcomes of the cancellation exchange described in {@link Frame}. */
    enum CancelStatus {
        /** The target's cancellation, execution and required cleanup have settled. */
        CANCELLED,
        /** The cancellation operation could not be carried out. */
        REJECTED,
        /** The target operation is no longer active on this connection. */
        NO_ACTIVE_REQUEST
    }

    /**
     * Requests connection shutdown without a reply. Closing a client transport does not implicitly
     * send or guarantee delivery of this frame.
     */
    record Bye() implements ClientFrame {}

    /** Client keep-alive sent when no business frame has been sent during the idle interval. */
    record Heartbeat() implements ClientFrame {}

    /** Application output with an explicit scope; connection-local output uses GlobalScope. */
    sealed interface ScopedFrame extends Frame
            permits Delta, Action, PluginAction, RequestAccepted, TurnOutcome, Veto, VetoResult {
        /**
         * Identifies where this output belongs; it does not authenticate its recipient.
         *
         * @return global, session or agent owner of this frame
         */
        @NonNull Scope scope();

        /**
         * Extracts a session identifier for routing without adding a redundant JSON field.
         *
         * @return session UUID for session/agent scopes, or null for global scope
         * @throws IllegalArgumentException if the scope contains an invalid UUID string
         */
        @JsonIgnore
        default UUID sessionId() {
            var scope = scope();
            var session = scope.session();
            return session == null ? null : UUID.fromString(session);
        }

        /**
         * Extracts an agent identifier for routing without adding a redundant JSON field.
         *
         * @return agent identifier for agent scope, or null for session/global scope
         */
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
                    TurnOutcome,
                    Input,
                    VetoResult {
        /**
         * Returns the sequence number echoed from the initiating sequenced request.
         *
         * @return the request sequence, or zero for a session-wide observation
         */
        long seq();
    }

    /** Ends an agent turn or command without necessarily closing its connection. */
    sealed interface TurnOutcome extends ServerFrame, ScopedFrame, SeqResponse permits Done, Error {
        /**
         * Identifies the agent turn being settled, independently of request correlation.
         *
         * @return turn number, or -1 for an outcome without an agent turn
         */
        int turnNumber();

        /**
         * Current login name in terminal command results; null when signed out or not applicable.
         *
         * @return terminal login name, or null
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
        /**
         * Creates a handshake response.
         *
         * @param version accepted wire protocol version
         * @param seq echoed nonzero Hello sequence
         * @param productVersion advertised backend build
         * @throws IllegalArgumentException if seq is zero
         */
        public Welcome {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * An autocomplete candidate returned in a {@link CompleteResult}.
     *
     * @param value the completion suggestion text (e.g. {@code /login})
     * @param description optional helpful description or context
     * @param group optional category/group name for terminal completion display
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
        /**
         * Creates a completion result with an immutable snapshot of the candidate list.
         *
         * @param candidates completion values; the list and its entries must be non-null
         * @param seq echoed nonzero Complete sequence
         * @throws IllegalArgumentException if seq is zero
         */
        public CompleteResult {
            candidates = List.copyOf(candidates);
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Information about the autocomplete hint for the next expected argument.
     *
     * @param placeholder template for the expected argument, or null when no hint is available
     * @param description optional explanation of the argument; null omits the explanation
     */
    record HintInfo(String placeholder, String description) {
        /** Empty placeholder instance. */
        public static final @NonNull HintInfo EMPTY = new HintInfo(null, null);

        /**
         * Returns the display text to render.
         *
         * @return placeholder and optional description, or an empty string when placeholder is null
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
        /**
         * Creates a result for a hint query.
         *
         * @param hint argument details, or HintInfo.EMPTY when there is no hint
         * @param seq echoed nonzero Hint sequence
         * @throws IllegalArgumentException if seq is zero
         */
        public HintResult {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Completes a command or agent turn. Scope identifies its owner, not terminal login state.
     * Cancellation is reported separately through CancelResult; this frame has no cancellation
     * flag.
     *
     * <p>Successful <a href="Frame.html#cancellation">cancellation</a> uses CancelResult instead;
     * do not send this correlated frame as an additional outcome for that target.
     *
     * @param scope owner of the completed operation
     * @param requestId agent runtime request identifier, or null when no agent request exists
     * @param turnNumber settled agent turn, or -1 for a command without an agent turn
     * @param content optional final text; null when no additional text is needed
     * @param username current terminal login name, or null when signed out/not applicable
     * @param seq initiating request sequence, or zero for an uncorrelated session observation
     */
    record Done(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Scope scope,
            String requestId,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber,
            String content,
            String username,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements TurnOutcome {}

    /**
     * Reports an operation error. During negotiation it rejects the connection; after Welcome it
     * settles the indicated operation without necessarily closing the connection.
     *
     * <p>An Error with a nonzero sequence settles only that exchange. An uncorrelated Error cannot
     * identify a pending request to release. Under the <a href="Frame.html#cancellation">
     * cancellation contract</a>, rejection of cancellation leaves normal operation errors possible,
     * but successful cancellation must not also produce a correlated Error for its target.
     *
     * @param scope owner of the failed operation or observation
     * @param requestId agent runtime request identifier, or null when no agent request exists
     * @param turnNumber failed agent turn, or -1 for an error without an agent turn
     * @param content error description
     * @param seq echoed from the initiating frame; 0 when not correlated to a sequenced request
     * @param username current terminal login name, or null when signed out/not applicable
     * @param code optional machine-readable error code; null when no code is supplied
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
            implements TurnOutcome {
        /**
         * Creates a connection-local error without agent or login metadata.
         *
         * @param content user-facing error description
         * @param seq initiating request sequence, or zero for an uncorrelated error
         */
        public Error(@NonNull String content, long seq) {
            this(new Scope.GlobalScope(), null, -1, content, seq, null, null);
        }

        /**
         * Creates an uncorrelated global error ({@code seq = 0}).
         *
         * @param content error description
         * @return the error frame
         */
        public static @NonNull Error ofError(@NonNull String content) {
            return new Error(content, 0);
        }
    }

    /**
     * Requests terminal text input while the owning command waits. The client answers with Input
     * using the same sequence; this is separate from plugin-managed questions.
     *
     * @param content prompt text shown to the user
     * @param mask whether the terminal should hide typed characters
     * @param seq nonzero sequence of the command waiting for input
     */
    record Prompt(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "mask", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean mask,
            @JsonProperty(value = "seq", required = true) @JsonSetter(nulls = Nulls.FAIL) long seq)
            implements ServerFrame, SeqRequest {
        /**
         * Creates a text prompt owned by a command.
         *
         * @param content displayed prompt text
         * @param mask whether to hide typed characters
         * @param seq nonzero sequence of the command waiting for input
         * @throws IllegalArgumentException if seq is zero
         */
        public Prompt {
            if (seq == 0) throw new IllegalArgumentException("Request sequence must be nonzero");
        }
    }

    /**
     * Approval request shared by browser and terminal; both clients use the actual agent scope.
     *
     * <p>Not unconditionally thread-safe: options are copied, but args and its nested values are
     * retained without copying or synchronization. Keep them read-only after publication.
     *
     * @param scope agent asking for approval
     * @param callId tool call identifier used to match the pending approval
     * @param tool tool name displayed to the user
     * @param scenario explanation of why approval is requested
     * @param options allowed reply values, copied on construction
     * @param args tool arguments displayed for review, retained by reference
     * @param danger optional screening danger level; null when unreported
     * @param relevance optional screening relevance level; null when unreported
     * @param seq server-allocated nonzero sequence echoed by VetoResult
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
        /**
         * Creates an approval request, copying options but retaining the argument tree.
         *
         * @param scope agent asking for approval
         * @param callId pending tool call identifier
         * @param tool displayed tool name
         * @param scenario explanation of the approval request
         * @param options allowed reply values; the list and entries must be non-null
         * @param args tool arguments; keep the reachable values read-only after publication
         * @param danger optional danger assessment
         * @param relevance optional relevance assessment
         * @param seq nonzero server request sequence
         * @throws IllegalArgumentException if seq is zero
         */
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
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
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
     *
     * @param scope agent scope echoed from Veto
     * @param callId pending tool call identifier echoed from Veto
     * @param option selected value from Veto.options
     * @param seq server request sequence echoed from Veto
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
        /**
         * Creates a reply to an agent's approval request.
         *
         * @param scope agent scope echoed from Veto
         * @param callId pending tool call identifier
         * @param option selected approval option
         * @param seq echoed nonzero Veto sequence
         * @throws IllegalArgumentException if seq is zero
         */
        public VetoResult {
            if (seq == 0)
                throw new IllegalArgumentException("Sequence must be nonzero; 0 is uncorrelated");
        }
    }

    /**
     * Tells clients which core resources to reload. Independent of turns and Done; plugin payloads
     * use PluginAction instead.
     *
     * @param scope session whose resources changed
     * @param resources nonempty list of core resource names to refresh, copied on construction
     */
    record Action(
            @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    Scope.@NonNull SessionScope scope,
            @JsonProperty(value = "resources", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull List<@NonNull String> resources)
            implements ServerFrame, ScopedFrame {
        /**
         * Creates a core refresh notification with a snapshot of its resource names.
         *
         * @param scope session whose resources changed
         * @param resources nonempty resource names; the list and entries must be non-null
         * @throws IllegalArgumentException if resources is empty
         */
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
     *
     * @param scope session receiving the plugin publication
     * @param topic plugin-defined topic used by its frontend to dispatch the payload
     * @param data plugin-defined JSON object, retained by reference; may carry the data to render
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
     * Requests that the client end this connection. It does not settle individual agent requests.
     *
     * @param reason optional explanation; null when none is supplied
     */
    record Terminate(String reason) implements ServerFrame {}

    /**
     * Keeps server-to-client traffic active when replying to an idle client's heartbeat. The client
     * does not correlate it with a pending probe or use its timestamp as a deadline.
     *
     * @param timestamp server wall-clock time when the reply was created, for observation only
     */
    record HeartbeatAck(
            @JsonProperty(value = "timestamp", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Instant timestamp)
            implements ServerFrame {}
}
