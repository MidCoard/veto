package top.focess.veto.agent.intercept;

import java.util.Map;
import org.jspecify.annotations.*;

/** Approval metadata shared by live execution and persisted-history replay. */
public record ApprovalReceipt(
        @NonNull VetoOption decision,
        InterceptResolution.@NonNull Source decisionSource,
        @NonNull String resolvedAt) {
    /**
     * Rebuilds a receipt from a stored approval payload; returns null when the value is not a
     * well-formed receipt.
     */
    // valueOf results are nullable to the NullnessChecker; the guard refines them for the
    // @NonNull record components.
    @SuppressWarnings("ConstantValue")
    public static ApprovalReceipt fromStored(Object value) {
        if (value instanceof ApprovalReceipt receipt) return receipt;
        if (!(value instanceof Map<?, ?> fields)
                || !(fields.get("decision") instanceof String decision)
                || !(fields.get("decisionSource") instanceof String source)) return null;
        try {
            VetoOption option = VetoOption.valueOf(decision);
            InterceptResolution.Source origin = InterceptResolution.Source.valueOf(source);
            if (option == null || origin == null) return null;
            return new ApprovalReceipt(
                    option,
                    origin,
                    fields.get("resolvedAt") instanceof String timestamp ? timestamp : "");
        } catch (IllegalArgumentException invalidReceipt) {
            return null;
        }
    }
}
