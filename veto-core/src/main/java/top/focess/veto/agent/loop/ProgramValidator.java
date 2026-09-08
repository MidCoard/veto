package top.focess.veto.agent.loop;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.model.tier.ModelTier;

/**
 * Validates an {@link ActionsProgram} before guided mode loads it. A program that fails validation
 * is rejected and the agent stays autonomous.
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
    public static void validate(@NonNull ActionsProgram program) {
        if (program.actions().isEmpty()) {
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
            if (a.id().isBlank()) {
                throw new InvalidProgramException("action id must not be blank at index " + i);
            }
            if (a.label().isBlank()) {
                throw new InvalidProgramException("action " + a.id() + ": label must not be blank");
            }
            if (!ids.add(a.id())) {
                throw new InvalidProgramException("duplicate action id: " + a.id());
            }
            if (a instanceof ToolAction t) {
                if (t.tool().isBlank()) {
                    throw new InvalidProgramException("action " + a.id() + ": tool required");
                }
            }
            if (a instanceof GenerateAction g) {
                Double temperature = g.temperature();
                if (temperature != null
                        && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
                    throw new InvalidProgramException("temperature must be between 0 and 2");
                String tier = g.modelTier();
                if (tier != null) {
                    try {
                        ModelTier.valueOf(tier);
                    } catch (IllegalArgumentException e) {
                        throw new InvalidProgramException("Unknown model_tier: " + tier);
                    }
                }
                validateBindings(new HashSet<>(g.inputs().keySet()), g.outputs());
                for (String field : g.outputs().values())
                    if (!Set.of("message", "thought").contains(field))
                        throw new InvalidProgramException("Unknown generate output: " + field);
            }
            if (a instanceof ToolAction t) validateBindings(Set.of(), t.outputs());
            if (a instanceof GotoAction g) {
                if (g.index() < 0 || g.index() >= n) {
                    throw new InvalidProgramException("goto out of range: " + g.index());
                }
            }
            if (a instanceof ConditionalGotoAction c) {
                if (c.trueGoto() < 0 || c.trueGoto() >= n) {
                    throw new InvalidProgramException("true_goto out of range: " + c.trueGoto());
                }
                Integer falseGoto = c.falseGoto();
                if (falseGoto != null && (falseGoto < 0 || falseGoto >= n)) {
                    throw new InvalidProgramException("false_goto out of range: " + falseGoto);
                }
            }
        }
        for (Action action : program.actions()) {
            if (action instanceof ConditionalGotoAction condition) {
                validateCheck(condition.check(), ids);
            }
        }
        boolean[] reachesStop = new boolean[n];
        boolean changed;
        do {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                Action action = program.actions().get(i);
                boolean reachable = action instanceof StopAction;
                if (action instanceof GotoAction jump) reachable = reachesStop[jump.index()];
                else if (action instanceof ConditionalGotoAction branch) {
                    Integer fallback = branch.falseGoto();
                    int next = fallback == null ? i + 1 : fallback;
                    reachable = reachesStop[branch.trueGoto()] || (next < n && reachesStop[next]);
                } else if (!reachable && i + 1 < n) reachable = reachesStop[i + 1];
                if (reachable && !reachesStop[i]) {
                    reachesStop[i] = true;
                    changed = true;
                }
            }
        } while (changed);
        for (boolean reachable : reachesStop)
            if (!reachable)
                throw new InvalidProgramException("Every action must have a path to STOP");
        if (!acyclic(program)) {
            throw new InvalidProgramException(
                    "goto/conditional_goto graph has a deterministic cycle");
        }
    }

    private static void validateCheck(@NonNull Check check, @NonNull Set<String> ids) {
        if (check instanceof Check.ExitOk exit && !ids.contains(exit.stepId()))
            throw new InvalidProgramException("exit_ok references unknown step: " + exit.stepId());
        if (check instanceof Check.Numeric numeric
                && !Set.of("gt", "lt", "eq", "gte", "lte").contains(numeric.op()))
            throw new InvalidProgramException("Unknown numeric comparison: " + numeric.op());
        if (check instanceof Check.Matches matches) {
            try {
                Pattern.compile(matches.regex());
            } catch (PatternSyntaxException e) {
                throw new InvalidProgramException("Invalid regex");
            }
        }
    }

    private static void validateBindings(
            @NonNull Set<String> inputs, @NonNull Map<String, String> outputs) {
        Set<String> names = new HashSet<>(inputs);
        names.addAll(outputs.keySet());
        for (String name : names) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.equals("CURRENT_STEPS"))
                throw new InvalidProgramException("Invalid or reserved binding name: " + name);
        }
    }

    /**
     * Detects a deterministic cycle (goto-only, ignoring conditional_goto branches that lead to
     * STOP).
     */
    private static boolean acyclic(@NonNull ActionsProgram program) {
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

    private static boolean hasCycle(
            @NonNull ActionsProgram p,
            int i,
            boolean @NonNull [] onStack,
            boolean @NonNull [] visited) {
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
        } else if (!(a instanceof ConditionalGotoAction)
                && !(a instanceof StopAction)
                && i + 1 < p.actions().size()) {
            cycle = hasCycle(p, i + 1, onStack, visited);
        }
        // Conditional loops are allowed; the runtime step budget bounds every action.
        onStack[i] = false;
        return cycle;
    }

    /** Thrown when an actions program fails validation. */
    public static final class InvalidProgramException extends RuntimeException {
        public InvalidProgramException(@NonNull String message) {
            super(message);
        }
    }
}
