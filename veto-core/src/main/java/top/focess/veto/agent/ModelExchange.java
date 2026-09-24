package top.focess.veto.agent;

import static top.focess.veto.util.LogValues.safe;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;

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
            SourceEvidence.Receipt citations,
            String modelCallId,
            boolean accepted) {}

    private final @NonNull String agentId;
    private final @NonNull ModelResponseValidation responses;

    ModelExchange(@NonNull String agentId, @NonNull ModelResponseValidation responses) {
        this.agentId = agentId;
        this.responses = responses;
    }

    @NonNull Result complete(
            @NonNull VetoRequest request,
            @NonNull ModelRequests requests,
            @NonNull Set<String> whitelistedTools,
            @NonNull Supplier<List<TurnRecord>> history,
            @NonNull Runtime runtime,
            double estimateFactor,
            @NonNull Object requestIdentity,
            @NonNull List<ModelResponsePolicy.Exchange> policies) {
        int schemaRetries = 0;
        VetoRequest correctionBase = request;
        Object boundary = new Object();
        for (; ; ) {
            try {
                request = runtime.prepare(request);
                correctionBase = runtime.prepare(correctionBase);
                var attempt = runtime.invoke(request, estimateFactor);
                request = attempt.request();
                var checked = attempt.response();
                responses.validateBase(checked, whitelistedTools);
                responses.validateResponseMode(checked, request);
                responses.validateLocalCallArguments(checked);
                SourceEvidence.Receipt receipt = null;
                ModelResponsePolicy.Correction correction = null;
                var active = new AtomicBoolean(true);
                var evidence =
                        new RequestEvidence(
                                requestIdentity,
                                boundary,
                                request,
                                attempt.modelCallId(),
                                history.get(),
                                active::get);
                try {
                    for (var policy : policies) {
                        var result = policy.check(checked, evidence);
                        checked = result.response();
                        if (result.receipt() != null) {
                            RequestEvidence.bound(result.receipt(), boundary);
                            receipt = result.receipt();
                        }
                        if (result.correction() != null) {
                            correction = result.correction();
                            break;
                        }
                    }
                } finally {
                    active.set(false);
                }
                if (correction != null) {
                    request =
                            requests.injectSchemaRejection(
                                    request,
                                    new ModelSchemaException(
                                            PromptCompiler.compileText(
                                                    correction.resource(), correction.data())));
                    continue;
                }
                responses.validateBase(checked, whitelistedTools);
                responses.validateResponseMode(checked, request);
                responses.validateLocalCallArguments(checked);
                verifySources(checked, receipt, boundary);
                return new Result(
                        request,
                        checked,
                        receipt == null
                                ? null
                                : RequestEvidence.seal(receipt, boundary, checked.message()),
                        attempt.modelCallId(),
                        true);
            } catch (ModelSchemaException failure) {
                log.warn(
                        "Agent {} schema violation (attempt {}): {}",
                        agentId,
                        schemaRetries + 1,
                        safe(failure.getMessage()));
                runtime.rejected(failure);
                for (var policy : policies) {
                    var retained = policy.rejected(schemaRetries);
                    if (retained != null) {
                        var receipt = retained.receipt();
                        RequestEvidence.bound(receipt, boundary);
                        verifySources(retained.response(), receipt, boundary);
                        return new Result(
                                receipt == null
                                        ? request
                                        : RequestEvidence.request(receipt, boundary),
                                retained.response(),
                                receipt == null
                                        ? null
                                        : RequestEvidence.seal(
                                                receipt, boundary, retained.response().message()),
                                RequestEvidence.modelCallId(receipt, boundary),
                                false);
                    }
                }
                schemaRetries++;
                request = requests.injectSchemaRejection(correctionBase, failure);
            }
        }
    }

    private void verifySources(
            @NonNull VetoResponse response,
            SourceEvidence.Receipt receipt,
            @NonNull Object boundary) {
        var citations = response.citations();
        if (citations != null
                && !citations.isEmpty()
                && (receipt == null
                        || !citations.equals(RequestEvidence.citations(receipt, boundary))))
            throw new ModelSchemaException(
                    "Declared sources require a matching host-issued receipt");
    }
}
