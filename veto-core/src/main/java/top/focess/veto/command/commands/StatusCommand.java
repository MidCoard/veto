package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.CredentialVault;

public class StatusCommand extends VetoCommand {

    private final CredentialVault vault;
    private final PromptHandler ph;

    public StatusCommand(CredentialVault vault, PromptHandler ph) {
        super("status", "Show session info");
        this.vault = vault;
        this.ph = ph;
        setExecutorPermission(LOGGED_IN);
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    String user = vault.getCurrentUser();
                    if (user == null) {
                        s.error("Not logged in.");
                        return refuse();
                    }
                    int sessions = ph.sessions().size();
                    int turns =
                            ph.sessions().values().stream().mapToInt(a -> a.turns().size()).sum();
                    s.done(
                            Map.of(
                                    "username",
                                    user,
                                    "turnNumber",
                                    turns,
                                    "headers",
                                    List.of("", ""),
                                    "rows",
                                    List.of(
                                            List.of("User", user),
                                            List.of("Sessions", String.valueOf(sessions)),
                                            List.of("Total turns", String.valueOf(turns)))));
                    return allow();
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/status — Show session info");
    }
}
