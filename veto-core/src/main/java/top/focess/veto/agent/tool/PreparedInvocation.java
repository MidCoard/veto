package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.integration.plugins.PluginProcessHosts;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-created binding between a selected contribution, its pure preparation and one call. */
public final class PreparedInvocation {
    private final @NonNull ManagedPlugin plugin;
    private final PluginHost.@NonNull Invocation invocation;
    private final @NonNull ToolCall call;
    private final ToolPreparation.@NonNull Intent intent;
    private final @NonNull String facts;
    private final @NonNull AtomicBoolean consumed = new AtomicBoolean();

    @SuppressWarnings(
            "resource") // WHY: the Running handle is owned by PluginProcessHosts, closed elsewhere
    PreparedInvocation(
            @NonNull ManagedPlugin plugin,
            PluginHost.@NonNull Invocation invocation,
            @NonNull ToolCall call,
            @NonNull ToolPreparation prepared,
            @NonNull ToolCapability capability) {
        if (capability != ToolCapability.PROCESS_EXECUTION)
            throw new SecurityException("Process intent requires process capability");
        this.plugin = plugin;
        this.invocation = invocation;
        this.call = call;
        var requested = prepared.intent();
        if (requested instanceof ToolPreparation.ProcessIntent process) {
            var limit =
                    process.timeout().isZero()
                                    || process.timeout().compareTo(Duration.ofMinutes(10)) > 0
                            ? Duration.ofMinutes(10)
                            : process.timeout();
            requested =
                    new ToolPreparation.ProcessIntent(
                            process.commands(), process.mode(), process.network(), limit);
        } else if (requested instanceof ToolPreparation.InputIntent input) {
            PluginProcessHosts.validateInput(plugin, invocation, input.process());
        }
        this.intent = requested;
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("pluginAdvisory", JsonValues.toMap(prepared.facts()));
        if (intent instanceof ToolPreparation.ProcessIntent process) {
            facts.put(
                    "approvedProcess",
                    Map.of(
                            "commands",
                            process.commands(),
                            "mode",
                            process.mode(),
                            "network",
                            process.network(),
                            "timeoutMillis",
                            process.timeout().toMillis()));
        } else if (intent instanceof ToolPreparation.InputIntent input) {
            facts.put(
                    "approvedInput",
                    Map.of(
                            "instance",
                            input.process().id(),
                            "command",
                            input.process().command(),
                            "cwd",
                            input.process().cwd(),
                            "network",
                            input.process().networkAllowed(),
                            "byteCount",
                            input.bytes().length,
                            "closeStdin",
                            input.closeStdin(),
                            "alive",
                            input.process().isAlive()));
        }
        try {
            this.facts = new ObjectMapper().writeValueAsString(facts);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid preparation facts", failure);
        }
    }

    public @NonNull String facts() {
        return facts;
    }

    /** Bounded host facts suitable for persisted screening reasons; never plugin advisory data. */
    @SuppressWarnings(
            "resource") // WHY: the Running handle is owned by PluginProcessHosts, closed elsewhere
    public @NonNull String summary() {
        if (intent instanceof ToolPreparation.ProcessIntent process)
            return "process commands="
                    + process.commands().size()
                    + ", network="
                    + process.network()
                    + ", timeoutMillis="
                    + process.timeout().toMillis();
        var input = (ToolPreparation.InputIntent) intent;
        return "process input instance="
                + input.process().id()
                + ", bytes="
                + input.bytes().length
                + ", closeStdin="
                + input.closeStdin()
                + ", network="
                + input.process().networkAllowed();
    }

    public ToolPreparation.@NonNull Intent intent() {
        return intent;
    }

    /** Re-checks that a prepared input effect's target process is still valid and alive. */
    public void revalidate() {
        if (intent instanceof ToolPreparation.InputIntent input)
            PluginProcessHosts.validateInput(plugin, invocation, input.process());
    }

    /**
     * Confirms this prepared effect belongs to the given owner and the current call context,
     * rejecting any mismatch, then {@linkplain #revalidate() revalidates} it.
     */
    public void authorize(@NonNull ManagedPlugin owner, @NonNull ToolCallContext context) {
        if (plugin != owner
                || !call.equals(context.executionPermit().call())
                || !invocation.agentId().equals(context.agentId())
                || !invocation.owner().equals(context.owner())
                || !invocation.sessionId().equals(String.valueOf(context.sessionId())))
            throw new SecurityException("Prepared effect belongs to another invocation");
        revalidate();
    }

    /** Marks the prepared effect consumed, rejecting a second consumption of the same effect. */
    public void consume() {
        if (!consumed.compareAndSet(false, true))
            throw new SecurityException("Prepared effect already consumed");
    }
}
