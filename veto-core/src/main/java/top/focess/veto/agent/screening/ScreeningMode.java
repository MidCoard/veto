package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;

/**
 * The user's runtime-tunable auto-approve cell set. CRITICAL is always REFUSED in every mode.
 * Otherwise: STRICT approves only (HIGH, SAFE); BALANCED approves (HIGH, SAFE), (HIGH, ELEVATED),
 * and (MEDIUM, SAFE); PERMISSIVE approves SAFE and ELEVATED calls regardless of relevance.
 * DANGEROUS always requires user authorization and CRITICAL is always refused.
 */
public enum ScreeningMode {
    STRICT {
        @Override
        public @NonNull ScreeningOutcome cell(@NonNull Relevance r, @NonNull Danger d) {
            if (d == Danger.CRITICAL) return ScreeningOutcome.REFUSED;
            if (r == Relevance.HIGH && d == Danger.SAFE) return ScreeningOutcome.APPROVE;
            return ScreeningOutcome.ASK;
        }
    },
    BALANCED {
        @Override
        public @NonNull ScreeningOutcome cell(@NonNull Relevance r, @NonNull Danger d) {
            if (d == Danger.CRITICAL) return ScreeningOutcome.REFUSED;
            if (r == Relevance.HIGH && (d == Danger.SAFE || d == Danger.ELEVATED))
                return ScreeningOutcome.APPROVE;
            if (r == Relevance.MEDIUM && d == Danger.SAFE) return ScreeningOutcome.APPROVE;
            return ScreeningOutcome.ASK;
        }
    },
    PERMISSIVE {
        @Override
        public @NonNull ScreeningOutcome cell(@NonNull Relevance r, @NonNull Danger d) {
            if (d == Danger.CRITICAL) return ScreeningOutcome.REFUSED;
            if (d == Danger.DANGEROUS) return ScreeningOutcome.ASK;
            return ScreeningOutcome.APPROVE;
        }
    };

    public abstract @NonNull ScreeningOutcome cell(
            @NonNull Relevance relevance, @NonNull Danger danger);
}
