package top.focess.veto.builtin.monitor;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code resume_monitor} - resume a paused monitor without stopping its observed work. */
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                """
                Resume a scheduled Monitor without stopping its observed work.\
                """,
        behavior =
                """
                Time-based rules wake this Agent through its normal execution loop. Paused or stopped Agents \
                are not automatically resumed. Group outcomes are observed automatically.\
                """,
        whenToUse =
                """
                Use for explicitly requested follow-up or to manage an existing scheduled wake-up.\
                """,
        whenNotToUse =
                """
                Do not schedule repeated checks for Group results; those arrive automatically. Do not use to \
                bypass a pause or task limit.\
                """,
        resultContract =
                """
                JSON containing the updated rule state. Failures: unknown id (failure, UNKNOWN): `Monitor not \
                found: <id detail>`; group-managed or missing session (failure, GROUP_MANAGED or \
                NO_SESSION_CONTEXT): `Monitor not updated: <reason>`.\
                """,
        errorsAndEdgeCases =
                """
                Unknown or inaccessible monitor ids (failure, UNKNOWN): `Monitor not found: no accessible \
                monitor has id <id>.`; group observation monitors cannot be controlled directly (failure, \
                GROUP_MANAGED); a missing session context (failure, NO_SESSION_CONTEXT). Offline wake-ups can \
                be delayed.\
                """,
        security =
                """
                Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is \
                granted.\
                """,
        examples = {
            "{\"monitorId\":\"3f6c9f4e-7b1a-4c2d-9e5f-2a8b6d1c4e70\"}",
            "{\"monitorId\":\"7a1e2c5b-3d6f-4e8a-9b0c-1d2e3f4a5b6c\"}",
            "{\"monitorId\":\"b8d2e4f6-1a3c-4b5d-9e7f-0a1b2c3d4e5f\"}"
        },
        returnExamples = {
            "{\"id\":\"3f6c9f4e-7b1a-4c2d-9e5f-2a8b6d1c4e70\",\"kind\":\"TIME_ONCE\",\"state\":\"ACTIVE\"}",
            "{\"id\":\"7a1e2c5b-3d6f-4e8a-9b0c-1d2e3f4a5b6c\",\"kind\":\"TIME_ONCE\",\"state\":\"ACTIVE\"}",
            "{\"id\":\"b8d2e4f6-1a3c-4b5d-9e7f-0a1b2c3d4e5f\",\"kind\":\"TIME_ONCE\",\"state\":\"ACTIVE\"}"
        })
public final class ResumeMonitorTool extends MonitorTool<ResumeMonitorTool.Args> {
    private final MonitorOperations capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public ResumeMonitorTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public ResumeMonitorTool(@NonNull MonitorOperations capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code resume_monitor}. */
    public record Args(@Doc("The Monitor id returned at creation.") @NonNull String monitorId) {}

    @Override
    public @NonNull String getName() {
        return "resume_monitor";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull MonitorOperations operations() {
        if (capability == null)
            throw new SecurityException("Monitor plugin operations are unavailable");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull MonitorOperations capability) {
        return controlMonitor(capability, args.monitorId(), "resume");
    }
}
