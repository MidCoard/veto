package top.focess.veto.api.plugin.contract;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoResponse;

/** Per-exchange feature policy. Host still enforces model schema, budget and evidence receipts. */
public interface ModelResponsePolicy {
    record Correction(@NonNull String resource, @NonNull Map<String, Object> data) {
        public Correction {
            data = Map.copyOf(data);
        }
    }

    record Result(
            @NonNull VetoResponse response,
            SourceEvidence.@Nullable Receipt receipt,
            @Nullable Correction correction) {}

    interface Exchange {
        @NonNull Result check(@NonNull VetoResponse response, @NonNull SourceEvidence evidence);

        /** Optional retained candidate after repeated generic schema rejection. */
        default @Nullable Result rejected(int failures) {
            return null;
        }
    }

    @NonNull Exchange open();
}
