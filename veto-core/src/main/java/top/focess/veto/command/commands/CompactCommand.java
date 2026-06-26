package top.focess.veto.command.commands;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

/**
 * Command to compact the active agent's turn history segment. Compresses the current role segment
 * and replaces verbose turns with a summary.
 */
public class CompactCommand extends VetoCommand {

    private final PromptHandler promptHandler;

    public CompactCommand(@NotNull PromptHandler promptHandler) {
        super("compact", "Summarize and compact the active agent's history segment");
        this.promptHandler = promptHandler;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    VetoAgent agent = (VetoAgent) promptHandler.sessions().get(s.terminalId());
                    if (agent == null) {
                        s.output("No active agent session. Activate an agent first.");
                        return CommandResult.REFUSE;
                    }

                    s.output("Initiating compaction on agent " + agent.name() + "...");
                    try {
                        agent.compact();
                        // Wait for compaction task to complete
                        var result = agent.await(java.time.Duration.ofMinutes(2));
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
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/compact — Summarize and compact the active agent's history segment");
    }
}
