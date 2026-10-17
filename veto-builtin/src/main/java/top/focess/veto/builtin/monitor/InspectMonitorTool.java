package top.focess.veto.builtin.monitor;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code inspect_monitor} - list registered monitors and pending notifications. */
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                """
                Inspect your registered Monitors and pending notifications.\
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
                JSON containing a list of registered rules. Missing session context (failure, \
                NO_SESSION_CONTEXT).\
                """,
        errorsAndEdgeCases =
                """
                Missing session context (failure, NO_SESSION_CONTEXT): `Monitors not listed: no active \
                session context.`. Offline wake-ups can be delayed.\
                """,
        security =
                """
                Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is \
                granted.\
                """,
        examples = "{}",
        returnExamples =
                "[{\"id\":\"3f6c9f4e-7b1a-4c2d-9e5f-2a8b6d1c4e70\",\"kind\":\"TIME_ONCE\",\"purpose\":\"Remind me to review the report\",\"state\":\"ACTIVE\"}]")
public final class InspectMonitorTool extends MonitorTool<InspectMonitorTool.Args> {
    private final MonitorOperations capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public InspectMonitorTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public InspectMonitorTool(@NonNull MonitorOperations capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code inspect_monitor}. */
    public record Args() {}

    @Override
    public @NonNull String getName() {
        return "inspect_monitor";
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
        return encodeResult(capability.inspect());
    }
}
