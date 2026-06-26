package top.focess.veto.memory;

/** The two long-term memory tiers per long_term_memory_tiers.md §2. */
public enum MemoryTier {
    /** Captured context of one session; visible only to that session; expires at session end. */
    SESSION,
    /** Learned insights promoted from Session LTM (or written directly); persistent, user-wide. */
    CROSS_SESSION
}
