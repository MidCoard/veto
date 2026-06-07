package top.focess.veto.command.commands;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.CredentialVault;

public class StatusCommand extends VetoCommand {

    private final CredentialVault vault;
    private final PromptHandler promptHandler;

    public StatusCommand(@NotNull CredentialVault vault, @NotNull PromptHandler promptHandler) {
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

                    String user = vault.getCurrentUser();
                    if (user == null) {
                        s.output("Not logged in.");
                        return CommandResult.REFUSE;
                    }

                    int sessionCount = promptHandler.sessions().size();
                    int turns =
                            promptHandler.sessions().values().stream()
                                    .mapToInt(a -> a.turns().size())
                                    .sum();

                    s.output("Session Status");
                    s.output(String.format("  User:            %s", user));
                    s.output(String.format("  Active sessions: %d", sessionCount));
                    s.output(String.format("  Total turns:     %d", turns));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/status — Show session info");
    }
}
