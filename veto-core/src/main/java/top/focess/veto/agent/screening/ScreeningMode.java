package top.focess.veto.agent.screening;

/**
 * The user's runtime-tunable auto-approve cell set. CRITICAL is always MUST_ASK in every mode;
 * (LOW, DANGEROUS) — the injection signature — is always ASK. Per screening_model.md §5.
 */
public enum ScreeningMode {
    STRICT {
        @Override
        public ScreeningOutcome cell(Relevance r, Danger d) {
            if (d == Danger.CRITICAL) return ScreeningOutcome.MUST_ASK;
            if (r == Relevance.HIGH && d == Danger.SAFE) return ScreeningOutcome.APPROVE;
            return ScreeningOutcome.ASK;
        }
    },
    BALANCED {
        @Override
        public ScreeningOutcome cell(Relevance r, Danger d) {
            if (d == Danger.CRITICAL) return ScreeningOutcome.MUST_ASK;
            if (d == Danger.SAFE) return ScreeningOutcome.APPROVE;
            if (r == Relevance.HIGH && d == Danger.ELEVATED) return ScreeningOutcome.APPROVE;
            return ScreeningOutcome.ASK;
        }
    },
    BYPASS_ALL {
        @Override
        public ScreeningOutcome cell(Relevance r, Danger d) {
            if (d == Danger.CRITICAL) return ScreeningOutcome.MUST_ASK;
            if (r == Relevance.LOW && d == Danger.DANGEROUS) return ScreeningOutcome.ASK;
            return ScreeningOutcome.APPROVE;
        }
    };

    public abstract ScreeningOutcome cell(Relevance relevance, Danger danger);
}
