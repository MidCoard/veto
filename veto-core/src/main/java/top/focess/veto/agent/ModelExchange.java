package top.focess.veto.agent;

import static top.focess.veto.util.LogValues.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.ResponseEnforcer;
import top.focess.veto.api.llm.ProviderMessages;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

/** Response enforcement and ephemeral correction, bounded by the runner's shared call budget. */
final class ModelExchange {
    private static final Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.ModelExchange");

    interface Runtime {
        @NonNull VetoRequest prepare(@NonNull VetoRequest request);

        @NonNull Attempt invoke(@NonNull VetoRequest request, double estimateFactor);

        void rejected(@NonNull ModelSchemaException error);
    }

    record Attempt(
            @NonNull VetoRequest request, @NonNull VetoResponse response, String modelCallId) {}

    record Result(
            @NonNull VetoRequest request,
            @NonNull VetoResponse response,
            MessageCitations.Bound citations,
            String modelCallId,
            boolean accepted) {}

    private record CitationMessage(int index, @NonNull String role, boolean toolCall) {}

    private final @NonNull String agentId;
    private final @NonNull ResponseValidator responses;

    ModelExchange(@NonNull String agentId, @NonNull ResponseValidator responses) {
        this.agentId = agentId;
        this.responses = responses;
    }

    @NonNull Result complete(
            @NonNull VetoRequest request,
            @NonNull ModelRequests requests,
            @NonNull Set<String> whitelistedTools,
            @NonNull Supplier<List<TurnRecord>> history,
            @NonNull Runtime runtime,
            double estimateFactor) {
        int schemaRetries = 0;
        @NonNull VetoRequest correctionBase = request;
        int citationRetries = 0;
        VetoResponse citationCandidate = null;
        String candidateModelCallId = null;
        MessageCitations.Bound candidateSources = null;
        for (; ; ) {
            @NonNull VetoResponse response;
            try {
                request = runtime.prepare(request);
                correctionBase = runtime.prepare(correctionBase);
                @NonNull Attempt attempt = runtime.invoke(request, estimateFactor);
                request = attempt.request();
                response = attempt.response();
                String modelCallId = attempt.modelCallId();

                @NonNull VetoResponse checked =
                        ResponseEnforcer.enforce(response, whitelistedTools);
                responses.validateResponseMode(checked, request);
                responses.validateLocalCallArguments(checked);
                MessageCitations.Bound citations = null;
                var declaredCitations = checked.citations();
                if (declaredCitations != null && !declaredCitations.isEmpty()) {
                    var bound = MessageCitations.bind(request, checked, history.get());
                    String citationError = null;
                    var messageGroups = ProviderMessages.groups(request);
                    for (var check : bound.checks()) {
                        for (var reference : check.references()) {
                            if (reference.status().equals("not_found") && citationError == null) {
                                int index = reference.messageIndex();
                                @NonNull String selected =
                                        index >= 0 && index < messageGroups.size()
                                                ? messageGroups.get(index).getFirst().role()
                                                : "";
                                citationError =
                                        PromptCompiler.compileText(
                                                "runtime-citation",
                                                Map.of(
                                                        "id",
                                                        check.id(),
                                                        "index",
                                                        reference.messageIndex(),
                                                        "selected",
                                                        selected,
                                                        "count",
                                                        bound.messageCount()));
                            }
                        }
                    }
                    if (citationError != null && citationRetries < 2) {
                        List<CitationMessage> order = new ArrayList<>();
                        for (int index = Math.max(0, messageGroups.size() - 64);
                                index < messageGroups.size();
                                index++) {
                            var item = messageGroups.get(index).getFirst();
                            order.add(
                                    new CitationMessage(
                                            index, item.role(), item.toolName() != null));
                        }
                        citationError =
                                PromptCompiler.compileText(
                                        "runtime-citation-order",
                                        Map.of("error", citationError, "items", order));
                        citationCandidate = checked;
                        candidateModelCallId = modelCallId;
                        candidateSources = bound;
                        citationRetries++;
                        log.warn(
                                "Agent {} citation correction {}: {}",
                                agentId,
                                citationRetries,
                                citationError);
                        request =
                                requests.injectSchemaRejection(
                                        request, new ModelSchemaException(citationError));
                        continue;
                    }
                    citations = bound;
                }
                return new Result(request, checked, citations, modelCallId, true);
            } catch (ModelSchemaException e) {
                log.warn(
                        "Agent {} schema violation (attempt {}): {}",
                        agentId,
                        schemaRetries + 1,
                        safe(e.getMessage()));
                runtime.rejected(e);
                if (schemaRetries >= 2 && citationCandidate != null) {
                    return new Result(
                            request,
                            citationCandidate,
                            candidateSources,
                            candidateModelCallId,
                            false);
                }
                schemaRetries++;
                // Inject an ephemeral rejection message so the model knows what to fix on retry.
                request = requests.injectSchemaRejection(correctionBase, e);
            }
        }
    }
}
