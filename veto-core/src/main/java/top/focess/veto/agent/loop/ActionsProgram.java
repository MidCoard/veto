package top.focess.veto.agent.loop;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A guided-mode actions program (the IR) — a single-use, ordered list of {@link Action}s authored
 * by the agent directly in its {@code VetoResponse.actionsProgram}. Parsed from the raw {@code
 * JsonNode} by {@code ActionsProgramParser} and validated by {@link ProgramValidator} before guided
 * mode loads it.
 *
 * <p>Single-use: never cached or reused. Discarded the moment guided mode exits (STOP, failure,
 * tripped check, voluntary deviation).
 */
public record ActionsProgram(@NonNull List<Action> actions) {

    public ActionsProgram {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
