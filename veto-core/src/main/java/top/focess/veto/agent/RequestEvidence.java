package top.focess.veto.agent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.ProviderMessages;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;

/** Host-issued source receipts for one exact model input and execution boundary. */
final class RequestEvidence implements SourceEvidence {
    /** Receipts may be forwarded once by the work which generated them. */
    static final class WorkSources {
        private final @NonNull Set<PluginWork.Source> issued =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;

        synchronized void check() {
            if (closed) throw new SecurityException("Generated-source work has completed");
        }

        synchronized boolean active() {
            return !closed;
        }

        synchronized void close() {
            closed = true;
            issued.clear();
        }

        synchronized void register(SourceEvidence.@Nullable Receipt receipt) {
            check();
            if (receipt != null) issued.add(receipt);
        }

        synchronized MessageCitations.@Nullable Bound consume(
                PluginWork.@Nullable Source receipt,
                @NonNull Object requestIdentity,
                String callId,
                @NonNull String message) {
            check();
            if (receipt == null) return null;
            if (!issued.contains(receipt))
                throw new SecurityException("Source receipt is not pending in this work");
            var bound = forRequest(receipt, requestIdentity, callId, message);
            issued.remove(receipt);
            return bound;
        }
    }

    private final @NonNull Object boundary;
    private final @NonNull Object requestIdentity;
    private final String modelCallId;
    private final @NonNull VetoRequest request;
    private final @NonNull List<TurnRecord> history;
    private final @NonNull BooleanSupplier active;

    RequestEvidence(
            @NonNull Object requestIdentity,
            @NonNull Object boundary,
            @NonNull VetoRequest request,
            String modelCallId,
            @NonNull List<TurnRecord> history,
            @NonNull BooleanSupplier active) {
        this.requestIdentity = requestIdentity;
        this.modelCallId = modelCallId;
        this.boundary = boundary;
        this.request = request;
        this.history = List.copyOf(history);
        this.active = active;
    }

    private void check() {
        if (!active.getAsBoolean()) throw new SecurityException("Source inspection has expired");
    }

    private record Issued(
            @NonNull Object requestIdentity,
            String modelCallId,
            String outputMessage,
            @NonNull Object boundary,
            @NonNull VetoRequest request,
            @NonNull List<VetoResponse.Citation> citations,
            MessageCitations.@NonNull Bound bound)
            implements Receipt {}

    public @NonNull Inspection inspect(@NonNull List<Declaration> declarations) {
        check();
        var response = MessageCitations.resolve(request, declarations);
        var citations = response.citations();
        return inspectResolved(citations == null ? List.of() : citations);
    }

    public @NonNull Inspection inspectResolved(@NonNull List<VetoResponse.Citation> declarations) {
        check();
        var citations = List.copyOf(declarations);
        var bound =
                MessageCitations.bind(
                        request, new VetoResponse(null, null, "", citations), history);
        var issues =
                bound.checks().stream()
                        .flatMap(
                                item ->
                                        item.references().stream()
                                                .filter(
                                                        reference ->
                                                                !reference
                                                                        .status()
                                                                        .equals("matched"))
                                                .map(
                                                        reference ->
                                                                new Issue(
                                                                        item.id(),
                                                                        reference.messageIndex(),
                                                                        reference.status())))
                        .toList();
        return new Inspection(
                citations,
                new Issued(requestIdentity, modelCallId, null, boundary, request, citations, bound),
                issues);
    }

    public @NonNull List<Message> messages() {
        check();
        var groups = ProviderMessages.groups(request);
        return java.util.stream.IntStream.range(0, groups.size())
                .mapToObj(
                        index ->
                                new Message(
                                        index,
                                        groups.get(index).getFirst().role(),
                                        groups.get(index).getFirst().toolName() != null))
                .toList();
    }

    static MessageCitations.@Nullable Bound bound(
            SourceEvidence.@Nullable Receipt receipt, @NonNull Object boundary) {
        if (receipt == null) return null;
        if (!(receipt instanceof Issued issued) || issued.boundary() != boundary)
            throw new SecurityException("Source receipt belongs to another exchange");
        return issued.bound();
    }

    static @NonNull List<VetoResponse.Citation> citations(
            SourceEvidence.@NonNull Receipt receipt, @NonNull Object boundary) {
        bound(receipt, boundary);
        return ((Issued) receipt).citations();
    }

    static SourceEvidence.@NonNull Receipt seal(
            SourceEvidence.@NonNull Receipt receipt, @NonNull Object boundary, String message) {
        bound(receipt, boundary);
        var issued = (Issued) receipt;
        return new Issued(
                issued.requestIdentity(),
                issued.modelCallId(),
                message,
                issued.boundary(),
                issued.request(),
                issued.citations(),
                issued.bound());
    }

    static MessageCitations.@Nullable Bound forRequest(
            PluginWork.@Nullable Source receipt,
            @NonNull Object requestIdentity,
            String modelCallId,
            @NonNull String message) {
        if (receipt == null) return null;
        if (!(receipt instanceof Issued issued)
                || issued.requestIdentity() != requestIdentity
                || !Objects.equals(issued.modelCallId(), modelCallId)
                || !message.equals(issued.outputMessage()))
            throw new SecurityException(
                    "Source receipt belongs to another request or generated result");
        return issued.bound();
    }

    static String modelCallId(SourceEvidence.@Nullable Receipt receipt, @NonNull Object boundary) {
        if (receipt == null) return null;
        bound(receipt, boundary);
        return ((Issued) receipt).modelCallId();
    }

    static @NonNull VetoRequest request(
            SourceEvidence.@NonNull Receipt receipt, @NonNull Object boundary) {
        bound(receipt, boundary);
        return ((Issued) receipt).request();
    }

    MessageCitations.@NonNull Bound finish(SourceEvidence.@NonNull Receipt receipt) {
        check();
        var bound = bound(receipt, boundary);
        if (bound == null) throw new SecurityException("Missing source receipt");
        var issued = (Issued) receipt;
        if (issued.request() != request)
            throw new SecurityException("Source receipt belongs to another model request");
        if (bound.checks().stream()
                .anyMatch(
                        item ->
                                item.references().stream()
                                        .anyMatch(
                                                reference ->
                                                        !reference.status().equals("matched"))))
            throw new IllegalArgumentException("Source receipt contains unmatched evidence");
        return bound;
    }
}
