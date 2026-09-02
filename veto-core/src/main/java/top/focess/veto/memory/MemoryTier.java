package top.focess.veto.memory;

/** The supported long-term memory visibility tiers. */
public enum MemoryTier {
    /** Captured context of one session; visible only to that session; expires at session end. */
    SESSION,
    /** Learned insights promoted from Session LTM (or written directly); persistent, user-wide. */
    CROSS_SESSION
}
