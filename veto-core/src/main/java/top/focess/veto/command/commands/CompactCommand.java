package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.agent.Agent;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

/**
 * Command to compact the active agent's turn history segment. Compresses the current role segment
 * and replaces verbose turns with a summary.
 */
public class CompactCommand extends VetoCommand {

    private final @NonNull PromptHandler promptHandler;
    private final @NonNull SessionCommandService commands;

    /** Constructs the {@code /compact} command resolving the active agent via the handler. */
    public CompactCommand(
            @NonNull PromptHandler promptHandler, @NonNull SessionCommandService commands) {
        super("compact", "Summarize and compact the active agent's history segment");
        this.promptHandler = promptHandler;
        this.commands = commands;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    Agent agent = promptHandler.activeAgent(s.terminalId());
                    if (agent == null) {
                        s.output("No active agent session. Activate an agent first.");
                        return CommandResult.REFUSE;
                    }

                    s.output("Initiating compaction on agent " + agent.name() + "...");
                    try {
                        var result = commands.compact(agent);
                        if (result.success()) {
                            s.output("Compaction completed successfully.");
                            return CommandResult.ALLOW;
                        } else {
                            s.output("Compaction failed: " + result.message());
                            return CommandResult.REFUSE;
                        }
                    } catch (Exception e) {
                        s.output("Compaction failed: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                });
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/compact — Summarize and compact the active agent's history segment");
    }
}
