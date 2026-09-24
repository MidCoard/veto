package top.focess.veto.builtin.planning;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A plan-mode actions program (the IR) — a single-use, ordered list of {@link Action}s authored
 * through the native {@code submit_plan} tool. Parsed from the raw {@code JsonNode} by {@code
 * ActionsProgramParser} and validated by ProgramValidator before plan mode loads it.
 *
 * <p>Single-use: never cached or reused. Discarded the moment plan mode exits (STOP, failure,
 * tripped check, voluntary deviation).
 */
public record ActionsProgram(@NonNull List<Action> actions) {

    public ActionsProgram {
        actions = List.copyOf(actions);
    }
}
