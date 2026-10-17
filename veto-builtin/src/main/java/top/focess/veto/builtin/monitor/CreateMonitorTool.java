package top.focess.veto.builtin.monitor;

import java.time.DateTimeException;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code create_monitor} - schedule a single wake-up within the next 30 days. */
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                """
                Schedule one wake-up within the next 30 days.\
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
                JSON containing registered rule state. Registration is not completion of future work. \
                Failures return `Monitor not created: <reason>`.\
                """,
        errorsAndEdgeCases =
                """
                Invalid times, missing purpose, and exhausted active-rule limits are rejected: argument \
                problems (failure, INVALID_ARGUMENTS), the 32-monitor limit (failure, LIMIT_EXCEEDED), and a \
                missing session context (failure, NO_SESSION_CONTEXT). Offline wake-ups can be delayed.\
                """,
        security =
                """
                Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is \
                granted.\
                """,
        examples = {
            "{\"purpose\":\"Remind me to review the report\",\"afterSeconds\":600}",
            "{\"purpose\":\"Summarize the CI results for the nightly build\",\"at\":\"2026-09-20T07:30:00+08:00\"}",
            "{\"purpose\":\"Re-check whether the example.com certificate renewal completed and report the new expiry date\",\"afterSeconds\":86400}",
            "{\"purpose\":\"Weekly digest\",\"afterSeconds\":604800,\"at\":\"2026-09-26T09:00:00+08:00\"}"
        },
        returnExamples = {
            "{\"id\":\"3f6c9f4e-7b1a-4c2d-9e5f-2a8b6d1c4e70\",\"kind\":\"TIME_ONCE\",\"purpose\":\"Remind me to review the report\",\"state\":\"ACTIVE\"}",
            "{\"id\":\"7a1e2c5b-3d6f-4e8a-9b0c-1d2e3f4a5b6c\",\"kind\":\"TIME_ONCE\",\"purpose\":\"Summarize the CI results for the nightly build\",\"state\":\"ACTIVE\"}",
            "{\"id\":\"b8d2e4f6-1a3c-4b5d-9e7f-0a1b2c3d4e5f\",\"kind\":\"TIME_ONCE\",\"purpose\":\"Re-check whether the example.com certificate renewal completed and report the new expiry date\",\"state\":\"ACTIVE\"}",
            "Monitor not created: supply exactly one of afterSeconds or at."
        })
public final class CreateMonitorTool extends MonitorTool<CreateMonitorTool.Args> {
    private final MonitorOperations capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public CreateMonitorTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public CreateMonitorTool(@NonNull MonitorOperations capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code create_monitor}. */
    public record Args(
            @Doc("What to do when woken.") @NonNull String purpose,
            @Doc("Delay in seconds; mutually exclusive with at.") Long afterSeconds,
            @Doc("Absolute ISO timestamp with offset; mutually exclusive with afterSeconds.")
                    String at) {}

    @Override
    public @NonNull String getName() {
        return "create_monitor";
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
        String purpose = args.purpose();
        Long afterSeconds = args.afterSeconds();
        String at = args.at();
        if ((afterSeconds == null) == (at == null))
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: supply exactly one of afterSeconds or at.");
        Instant due;
        try {
            due =
                    afterSeconds != null
                            ? Instant.now().plusSeconds(afterSeconds)
                            : Instant.parse(at.strip());
        } catch (DateTimeException error) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: at must be an ISO-8601 timestamp with an offset, such as"
                            + " 2026-09-20T07:30:00+08:00.");
        }
        if (purpose.isBlank())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: purpose must not be blank.");
        Instant now = Instant.now();
        if (!due.isAfter(now) || due.isAfter(now.plusSeconds(30L * 24 * 3600)))
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: choose a future time within 30 days.");
        try {
            return encodeResult(capability.create(purpose, due));
        } catch (IllegalStateException error) {
            return ToolErrors.failure(
                    MonitorError.LIMIT_EXCEEDED,
                    "Monitor not created: this agent already has 32 active or paused monitors.");
        }
    }
}
