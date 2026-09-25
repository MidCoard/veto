package top.focess.veto.api.plugin.contract;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoResponse;

/** Per-exchange feature policy. Host still enforces model schema, budget and evidence receipts. */
public interface ModelResponsePolicy {
    /**
     * Structured correction prompt requested after policy rejection.
     *
     * @param resource host-resolved prompt resource
     * @param data immutable interpolation data copied on construction
     */
    record Correction(@NonNull String resource, @NonNull Map<String, Object> data) {
        /** Defensively copies the interpolation data. */
        public Correction {
            data = Map.copyOf(data);
        }
    }

    /**
     * Checked response and optional evidence and retry policy.
     *
     * @param response accepted or corrected model response
     * @param receipt optional evidence receipt
     * @param correction optional correction for a subsequent attempt
     */
    record Result(
            @NonNull VetoResponse response,
            SourceEvidence.@Nullable Receipt receipt,
            @Nullable Correction correction) {}

    /** Mutable policy state scoped to one model exchange. */
    interface Exchange {
        /**
         * Checks one response against the host-provided evidence view.
         *
         * @param response model response to evaluate
         * @param evidence host-provided evidence view
         * @return the checked response and retry policy
         */
        @NonNull Result check(@NonNull VetoResponse response, @NonNull SourceEvidence evidence);

        /**
         * Returns an optional retained candidate after repeated generic schema rejection.
         *
         * @param failures number of generic schema failures
         * @return retained result, or {@code null} when none should be used
         */
        default @Nullable Result rejected(int failures) {
            return null;
        }
    }

    /**
     * Opens policy state isolated to a single model exchange.
     *
     * @return independent policy state for one exchange
     */
    @NonNull Exchange open();
}
