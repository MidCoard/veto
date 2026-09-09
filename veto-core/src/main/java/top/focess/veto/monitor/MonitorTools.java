package top.focess.veto.monitor;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.MonitorCapability;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.MonitorTool;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolResultFormat;

public final class MonitorTools {
    private MonitorTools() {}

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Schedule one wake-up within the next 30 days.",
            behavior =
                    "Time-based rules wake this Agent through its normal execution loop. Paused or stopped Agents are not automatically resumed. Group outcomes are observed automatically.",
            whenToUse =
                    "Use for explicitly requested follow-up or to manage an existing scheduled wake-up.",
            whenNotToUse =
                    "Do not schedule repeated checks for Group results; those arrive automatically. Do not use to bypass a pause or task limit.",
            resultContract =
                    "JSON containing registered rule state, or a list of rules. Registration is not completion of future work.",
            errorsAndEdgeCases =
                    "Invalid times, missing purpose, inaccessible rules and exhausted active-rule limits are rejected. Offline wake-ups can be delayed.",
            security =
                    "Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is granted.",
            examples = "{\"purpose\":\"Remind me to review the report\",\"afterSeconds\":600}",
            returnExamples = "{\"state\":\"ACTIVE\",\"kind\":\"TIME_ONCE\"}")
    public static final class CreateMonitor implements MonitorTool<CreateMonitor.Args> {
        private final @NonNull MonitorCapability capability;

        public CreateMonitor(@NonNull MonitorCapability capability) {
            this.capability = capability;
        }

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
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull MonitorCapability monitorCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(@NonNull Args args, @NonNull MonitorCapability capability) {
            return capability.create(args.purpose(), args.afterSeconds(), args.at());
        }
    }

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Inspect your registered Monitors and pending notifications.",
            behavior =
                    "Time-based rules wake this Agent through its normal execution loop. Paused or stopped Agents are not automatically resumed. Group outcomes are observed automatically.",
            whenToUse =
                    "Use for explicitly requested follow-up or to manage an existing scheduled wake-up.",
            whenNotToUse =
                    "Do not schedule repeated checks for Group results; those arrive automatically. Do not use to bypass a pause or task limit.",
            resultContract =
                    "JSON containing registered rule state, or a list of rules. Registration is not completion of future work.",
            errorsAndEdgeCases =
                    "Invalid times, missing purpose, inaccessible rules and exhausted active-rule limits are rejected. Offline wake-ups can be delayed.",
            security =
                    "Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is granted.",
            examples = "{}",
            returnExamples = "{\"state\":\"ACTIVE\",\"kind\":\"TIME_ONCE\"}")
    public static final class InspectMonitor implements MonitorTool<InspectMonitor.Args> {
        private final @NonNull MonitorCapability capability;

        public InspectMonitor(@NonNull MonitorCapability capability) {
            this.capability = capability;
        }

        public record Args() {}

        @Override
        public @NonNull String getName() {
            return "inspect_monitor";
        }

        @Override
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull MonitorCapability monitorCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(@NonNull Args args, @NonNull MonitorCapability capability) {
            return capability.inspect();
        }
    }

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Pause a scheduled Monitor without stopping its observed work.",
            behavior =
                    "Time-based rules wake this Agent through its normal execution loop. Paused or stopped Agents are not automatically resumed. Group outcomes are observed automatically.",
            whenToUse =
                    "Use for explicitly requested follow-up or to manage an existing scheduled wake-up.",
            whenNotToUse =
                    "Do not schedule repeated checks for Group results; those arrive automatically. Do not use to bypass a pause or task limit.",
            resultContract =
                    "JSON containing registered rule state, or a list of rules. Registration is not completion of future work.",
            errorsAndEdgeCases =
                    "Invalid times, missing purpose, inaccessible rules and exhausted active-rule limits are rejected. Offline wake-ups can be delayed.",
            security =
                    "Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is granted.",
            examples = "{\"monitorId\":\"<monitor-id>\"}",
            returnExamples = "{\"state\":\"ACTIVE\",\"kind\":\"TIME_ONCE\"}")
    public static final class PauseMonitor implements MonitorTool<PauseMonitor.Args> {
        private final @NonNull MonitorCapability capability;

        public PauseMonitor(@NonNull MonitorCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @Doc("The Monitor id returned at creation.") @NonNull String monitorId) {}

        @Override
        public @NonNull String getName() {
            return "pause_monitor";
        }

        @Override
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull MonitorCapability monitorCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(@NonNull Args args, @NonNull MonitorCapability capability) {
            return capability.control(args.monitorId(), "pause");
        }
    }

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Resume a scheduled Monitor without stopping its observed work.",
            behavior =
                    "Time-based rules wake this Agent through its normal execution loop. Paused or stopped Agents are not automatically resumed. Group outcomes are observed automatically.",
            whenToUse =
                    "Use for explicitly requested follow-up or to manage an existing scheduled wake-up.",
            whenNotToUse =
                    "Do not schedule repeated checks for Group results; those arrive automatically. Do not use to bypass a pause or task limit.",
            resultContract =
                    "JSON containing registered rule state, or a list of rules. Registration is not completion of future work.",
            errorsAndEdgeCases =
                    "Invalid times, missing purpose, inaccessible rules and exhausted active-rule limits are rejected. Offline wake-ups can be delayed.",
            security =
                    "Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is granted.",
            examples = "{\"monitorId\":\"<monitor-id>\"}",
            returnExamples = "{\"state\":\"ACTIVE\",\"kind\":\"TIME_ONCE\"}")
    public static final class ResumeMonitor implements MonitorTool<ResumeMonitor.Args> {
        private final @NonNull MonitorCapability capability;

        public ResumeMonitor(@NonNull MonitorCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @Doc("The Monitor id returned at creation.") @NonNull String monitorId) {}

        @Override
        public @NonNull String getName() {
            return "resume_monitor";
        }

        @Override
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull MonitorCapability monitorCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(@NonNull Args args, @NonNull MonitorCapability capability) {
            return capability.control(args.monitorId(), "resume");
        }
    }

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Cancel a scheduled Monitor without stopping its observed work.",
            behavior =
                    "Time-based rules wake this Agent through its normal execution loop. Paused or stopped Agents are not automatically resumed. Group outcomes are observed automatically.",
            whenToUse =
                    "Use for explicitly requested follow-up or to manage an existing scheduled wake-up.",
            whenNotToUse =
                    "Do not schedule repeated checks for Group results; those arrive automatically. Do not use to bypass a pause or task limit.",
            resultContract =
                    "JSON containing registered rule state, or a list of rules. Registration is not completion of future work.",
            errorsAndEdgeCases =
                    "Invalid times, missing purpose, inaccessible rules and exhausted active-rule limits are rejected. Offline wake-ups can be delayed.",
            security =
                    "Owner, Session and Agent are taken from the execution permit. No arbitrary resource access is granted.",
            examples = "{\"monitorId\":\"<monitor-id>\"}",
            returnExamples = "{\"state\":\"ACTIVE\",\"kind\":\"TIME_ONCE\"}")
    public static final class CancelMonitor implements MonitorTool<CancelMonitor.Args> {
        private final @NonNull MonitorCapability capability;

        public CancelMonitor(@NonNull MonitorCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @Doc("The Monitor id returned at creation.") @NonNull String monitorId) {}

        @Override
        public @NonNull String getName() {
            return "cancel_monitor";
        }

        @Override
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull MonitorCapability monitorCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(@NonNull Args args, @NonNull MonitorCapability capability) {
            return capability.control(args.monitorId(), "cancel");
        }
    }
}
