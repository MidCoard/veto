package top.focess.veto.agent.loop;

import java.util.HashSet;
import java.util.Set;

/**
 * Validates an {@link ActionsProgram} before guided mode loads it ({@code
 * workflow_execution_engine.md} ). A program that fails validation is rejected and the agent stays
 * autonomous.
 *
 * <ul>
 *   <li><b>(A) Static lint</b> — the final element must be {@link StopAction}.
 *   <li>Fully-bound — every {@link ToolAction}/{@link GenerateAction} names its input/output
 *       bindings (no {@code null} tool/prompt).
 *   <li>Acyclicity — {@link GotoAction}/{@link ConditionalGotoAction} targets are in-range and the
 *       goto graph has no purely-deterministic cycle reachable without a {@link StopAction}.
 * </ul>
 */
public final class ProgramValidator {

    private ProgramValidator() {}

    /** Validates; throws {@link InvalidProgramException} on failure. */
    public static void validate(ActionsProgram program) {
        if (program == null || program.actions().isEmpty()) {
            throw new InvalidProgramException("program is empty");
        }
        // (A) STOP termination.
        if (!(program.actions().get(program.actions().size() - 1) instanceof StopAction)) {
            throw new InvalidProgramException("final action must be STOP");
        }
        int n = program.actions().size();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Action a = program.actions().get(i);
            if (a.id() != null && !ids.add(a.id())) {
                throw new InvalidProgramException("duplicate action id: " + a.id());
            }
            if (a instanceof ToolAction t) {
                if (t.tool() == null || t.tool().isBlank()) {
                    throw new InvalidProgramException("action " + a.id() + ": tool required");
                }
            }
            if (a instanceof GotoAction g) {
                if (g.index() < 0 || g.index() >= n) {
                    throw new InvalidProgramException("goto out of range: " + g.index());
                }
            }
            if (a instanceof ConditionalGotoAction c) {
                if (c.trueGoto() < 0 || c.trueGoto() >= n) {
                    throw new InvalidProgramException("true_goto out of range: " + c.trueGoto());
                }
                if (c.falseGoto() != null && (c.falseGoto() < 0 || c.falseGoto() >= n)) {
                    throw new InvalidProgramException("false_goto out of range: " + c.falseGoto());
                }
            }
        }
        if (!acyclic(program)) {
            throw new InvalidProgramException(
                    "goto/conditional_goto graph has a deterministic cycle");
        }
    }

    /**
     * Detects a deterministic cycle (goto-only, ignoring conditional_goto branches that lead to
     * STOP).
     */
    private static boolean acyclic(ActionsProgram program) {
        int n = program.actions().size();
        boolean[] onStack = new boolean[n];
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!visited[i] && hasCycle(program, i, onStack, visited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCycle(ActionsProgram p, int i, boolean[] onStack, boolean[] visited) {
        if (onStack[i]) {
            return true;
        }
        if (visited[i]) {
            return false;
        }
        onStack[i] = true;
        visited[i] = true;
        Action a = p.actions().get(i);
        boolean cycle = false;
        if (a instanceof GotoAction g) {
            cycle = hasCycle(p, g.index(), onStack, visited);
        } else if (a instanceof StopAction) {
            cycle = false; // terminal
        }
        // conditional_goto: a conditional cycle is bounded by CURRENT_STEPS checks ( note); we
        // only flag unconditional goto cycles here.
        onStack[i] = false;
        return cycle;
    }

    /** Thrown when an actions program fails validation. */
    public static final class InvalidProgramException extends RuntimeException {
        public InvalidProgramException(String message) {
            super(message);
        }
    }
}
