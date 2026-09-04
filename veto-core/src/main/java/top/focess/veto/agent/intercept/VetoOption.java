package top.focess.veto.agent.intercept;

/**
 * A resolution option offered at a veto prompt. The offered set is determined by the tool's {@link
 * VetoScenario}. The {@code _LIKE_THIS} variants create a permission grant that converts future
 * matching {@code ASK} calls into {@code APPROVE} without a prompt; the other accept variants
 * approve only the current call. Read results can also be explicitly masked before entering model
 * context.
 */
public enum VetoOption {
    ACCEPT_AND_MASK_READ,
    ACCEPT_AND_MASK_READ_LIKE_THIS,
    ACCEPT_READ,
    ACCEPT_READ_LIKE_THIS,
    READ_DECLINE,

    ACCEPT_WRITE,
    ACCEPT_WRITE_LIKE_THIS,

    ABORT_WRITE,
    REREAD,
    FORCE_OVERWRITE,

    BLOCK,
    OVERRIDE,

    ACCEPT_COMMAND,
    ACCEPT_COMMAND_LIKE_THIS,
    EXEC_DECLINE,

    ACCEPT_COMMAND_ONCE,
    ACCEPT_COMMAND_AS_SESSION_RULE,

    ACCEPT_GENERIC,
    ACCEPT_GENERIC_LIKE_THIS,
    GENERIC_DECLINE,

    DECLINE_AND_CONTINUE;

    /** Whether this option creates a permission grant. */
    public boolean createsGrant() {
        return this == ACCEPT_AND_MASK_READ_LIKE_THIS
                || this == ACCEPT_READ_LIKE_THIS
                || this == ACCEPT_WRITE_LIKE_THIS
                || this == ACCEPT_COMMAND_LIKE_THIS
                || this == ACCEPT_COMMAND_AS_SESSION_RULE
                || this == ACCEPT_GENERIC_LIKE_THIS;
    }

    /** Whether this is the generic grant option. */
    public boolean isGenericGrant() {
        return this == ACCEPT_GENERIC_LIKE_THIS;
    }

    /** Whether this option masks the resulting observation. */
    public boolean impliesMasking() {
        return this == ACCEPT_AND_MASK_READ || this == ACCEPT_AND_MASK_READ_LIKE_THIS;
    }

    /** Whether this option refuses the call. */
    public boolean isRefusal() {
        return this == READ_DECLINE
                || this == ABORT_WRITE
                || this == BLOCK
                || this == EXEC_DECLINE
                || this == GENERIC_DECLINE
                || this == DECLINE_AND_CONTINUE;
    }

    /** Whether this is the per-call refuse-and-continue variant. */
    public boolean isDeclineAndContinue() {
        return this == DECLINE_AND_CONTINUE;
    }
}
