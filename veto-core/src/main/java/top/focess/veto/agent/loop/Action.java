package top.focess.veto.agent.loop;

import java.util.Map;

/**
 * One step of a guided-mode {@link ActionsProgram} (the IR). Transcribed from {@code
 * prompt_react_syntax.md} §2.4 + {@code workflow_execution_engine.md} §3. The agent authors this
 * directly in its {@code VetoResponse.actionsProgram} (no {@code plan} tool); the harness parses
 * and validates it before loading into guided mode. Every element carries an {@code id} + {@code
 * label}; the {@code type} discriminator is realized as the record type.
 *
 * <p>Three families:
 *
 * <ul>
 *   <li>{@link ToolAction} — fully-bound deterministic tool; may execute with no model call.
 *   <li>{@link GenerateAction} — the only action that invokes the model (scoped, in shared
 *       context).
 *   <li>Transitions — {@link GotoAction}, {@link ConditionalGotoAction}, {@link StopAction}: zero
 *       model calls, harness-driven control flow.
 * </ul>
 */
public sealed interface Action
        permits ToolAction, GenerateAction, GotoAction, ConditionalGotoAction, StopAction {

    /** Unique within the program. */
    String id();

    /** Human-readable, for logging. */
    String label();

    /**
     * Resolves {@code $var|literal} input bindings against the {@link Scope} into concrete args.
     */
    default Map<String, Object> resolveInputs(Scope scope) {
        return Map.of();
    }
}
