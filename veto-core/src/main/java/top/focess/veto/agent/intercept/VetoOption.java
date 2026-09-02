package top.focess.veto.agent.intercept;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A resolution option offered at a veto prompt. The offered set is determined by the tool's {@link
 * VetoScenario}. The {@code _LIKE_THIS} variants create a permission grant that converts future
 * matching {@code ASK} calls into {@code APPROVE} without a prompt; the non-{@code _LIKE_THIS}
 * variants approve just this call. The {@code ACCEPT_AND_MASK_*} variants apply output masking to
 * the result before it enters context; the plain {@code ACCEPT_*} variants do not.
 */
@SuppressWarnings(
        "DeprecatedIsStillUsed") // Legacy aliases intentionally remain behaviorally active.
public enum VetoOption {
    // ── Scenario R (read tools: view_file / list_dir / grep_search) ───────────
    /** Read tool: scrub secrets from the result (default-on if user did not explicitly choose). */
    ACCEPT_AND_MASK_READ,
    /** Read tool: scrub secrets + create a permission grant for matching future calls. */
    ACCEPT_AND_MASK_READ_LIKE_THIS,
    /** Read tool: verbatim result, no masking. */
    ACCEPT_READ,
    /** Read tool: verbatim + create a permission grant for matching future calls. */
    ACCEPT_READ_LIKE_THIS,
    /** Read tool: decline the read. */
    READ_DECLINE,

    // ── Write tools (non-drift) — write_to_file / replace_file_content ────────
    /**
     * Write tool: scrub secrets from any observation (default-on if user did not explicitly
     * choose).
     */
    ACCEPT_AND_MASK_WRITE,
    /** Write tool: scrub + create a permission grant for matching future calls. */
    ACCEPT_AND_MASK_WRITE_LIKE_THIS,
    /** Write tool: verbatim, no masking. */
    ACCEPT_WRITE,
    /** Write tool: verbatim + create a permission grant for matching future calls. */
    ACCEPT_WRITE_LIKE_THIS,

    // ── Scenario W (write drift — write_to_file / replace_file_content) ───────
    ABORT_WRITE,
    REREAD,
    FORCE_OVERWRITE,
    EDIT,

    // ── Scenario E1 (run_command / network deterministic trip) ────────────────
    BLOCK,
    OVERRIDE,

    // ── Scenario E2 (run_command / network — SLM semantic flag) ───────────────
    /** Exec: scrub secrets from the result. */
    ACCEPT_AND_MASK_COMMAND,
    /** Exec: scrub secrets + create a permission grant for matching future calls. */
    ACCEPT_AND_MASK_COMMAND_LIKE_THIS,
    /** Exec: verbatim, no masking. */
    ACCEPT_COMMAND,
    /** Exec: verbatim + create a permission grant for matching future calls. */
    ACCEPT_COMMAND_LIKE_THIS,
    /** Exec: decline. */
    EXEC_DECLINE,

    // ── Scenario E3 (run_command / network — first-time pattern) ──────────────
    ACCEPT_COMMAND_ONCE,
    ACCEPT_COMMAND_AS_SESSION_RULE,

    // ── Generic fallback (external MCP tool that declares no custom options) ─
    ACCEPT_GENERIC,
    ACCEPT_GENERIC_LIKE_THIS,
    GENERIC_DECLINE,

    // ── Per-call refuse-and-continue (inside the approve payload) ────────────
    DECLINE_AND_CONTINUE,

    // ── Legacy aliases (backward-compat for older callers) ────────────────────
    // The per-tool option sets supersede these aliases. They remain so older call sites and tests
    // still compile; new code should use the per-tool names above.
    @Deprecated
    READ_MASK,
    @Deprecated
    READ_ALLOW,
    @Deprecated
    READ_DENY,
    @Deprecated
    ACCEPT,
    @Deprecated
    ACCEPT_REDACTED,
    @Deprecated
    DECLINE,
    @Deprecated
    ACCEPT_ONCE,
    @Deprecated
    ACCEPT_AS_SESSION_RULE;

    /** Whether this option creates a permission grant. */
    public boolean createsGrant() {
        return this == ACCEPT_AND_MASK_READ_LIKE_THIS
                || this == ACCEPT_READ_LIKE_THIS
                || this == ACCEPT_AND_MASK_WRITE_LIKE_THIS
                || this == ACCEPT_WRITE_LIKE_THIS
                || this == ACCEPT_AND_MASK_COMMAND_LIKE_THIS
                || this == ACCEPT_COMMAND_LIKE_THIS
                || this == ACCEPT_COMMAND_AS_SESSION_RULE
                || this == ACCEPT_GENERIC_LIKE_THIS
                || this == ACCEPT_AS_SESSION_RULE; // legacy alias
    }

    /** Whether this is the generic grant option or its legacy session-rule alias. */
    public boolean isGenericGrant() {
        return this == ACCEPT_GENERIC_LIKE_THIS || this == ACCEPT_AS_SESSION_RULE;
    }

    /** Whether this option implies output masking should be applied to the observation. */
    public boolean impliesMasking() {
        return this == ACCEPT_AND_MASK_READ
                || this == ACCEPT_AND_MASK_READ_LIKE_THIS
                || this == ACCEPT_AND_MASK_WRITE
                || this == ACCEPT_AND_MASK_WRITE_LIKE_THIS
                || this == ACCEPT_AND_MASK_COMMAND
                || this == ACCEPT_AND_MASK_COMMAND_LIKE_THIS
                || this == ACCEPT_REDACTED
                || this == READ_MASK; // legacy alias
    }

    /** Whether this option refuses the call. */
    public boolean isRefusal() {
        return this == READ_DECLINE
                || this == ABORT_WRITE
                || this == BLOCK
                || this == EXEC_DECLINE
                || this == GENERIC_DECLINE
                || this == DECLINE
                || this == READ_DENY
                || this == DECLINE_AND_CONTINUE;
    }

    /** Whether this option is the per-call refuse-and-continue variant. */
    public boolean isDeclineAndContinue() {
        return this == DECLINE_AND_CONTINUE;
    }

    /**
     * The options offered to the user with {@link #EDIT} removed. v1 wires the veto reply as a raw
     * option-name string (an {@code Input} frame), which can't carry the edited args {@link #EDIT}
     * requires - so EDIT is omitted from the offered set (it only appears in the WRITE_DRIFT
     * scenario). The upgrade path is an optional {@code editedArgs} on {@code Input} or a follow-up
     * frame.
     */
    public static @NonNull List<VetoOption> withoutEdit(@NonNull List<VetoOption> options) {
        if (options.isEmpty()) {
            return List.of();
        }
        List<VetoOption> out = new ArrayList<>(options.size());
        for (VetoOption o : options) {
            if (o != EDIT) {
                out.add(o);
            }
        }
        return List.copyOf(out);
    }
}
