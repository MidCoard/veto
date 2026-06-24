package top.focess.veto.agent.loop;

import java.util.List;

/**
 * A guided-mode actions program (the IR) — a single-use, ordered list of {@link Action}s authored
 * by the agent directly in its {@code VetoResponse.actionsProgram} (LLD {@code
 * workflow_execution_engine.md} §3, §11). Parsed from the raw {@code JsonNode} by {@code
 * ActionsProgramParser} and validated by {@link ProgramValidator} before guided mode loads it.
 *
 * <p>Single-use: never cached or reused. Discarded the moment guided mode exits (STOP, failure,
 * tripped check, voluntary deviation).
 */
public record ActionsProgram(List<Action> actions) {

    public ActionsProgram {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
