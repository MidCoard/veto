package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.KeysteadVault;

public class StatusCommand extends VetoCommand {

    private final @NonNull KeysteadVault vault;
    private final @NonNull PromptHandler promptHandler;

    public StatusCommand(@NonNull KeysteadVault vault, @NonNull PromptHandler promptHandler) {
        super("status", "Show session info");
        this.vault = vault;
        this.promptHandler = promptHandler;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    String user = vault.currentUser();
                    if (user == null) {
                        s.output("Not logged in.");
                        return CommandResult.REFUSE;
                    }

                    int totalSessions = promptHandler.sessions().size();
                    int turns =
                            promptHandler.sessions().values().stream()
                                    .mapToInt(a -> a.history().size())
                                    .sum();

                    s.output("Session Status");
                    s.output(String.format("  User:            %s", user));
                    s.output(String.format("  Total sessions:  %d", totalSessions));
                    s.output(String.format("  Total turns:     %d", turns));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/status — Show session info");
    }
}
