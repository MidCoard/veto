package top.focess.veto.command.commands;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.vault.CredentialVault;

public class LogoutCommand extends VetoCommand {

    private final CredentialVault vault;
    private final TerminalSessionManager sessions;
    private final PromptHandler promptHandler;

    public LogoutCommand(
            @NotNull CredentialVault vault,
            @NotNull TerminalSessionManager sessions,
            @NotNull PromptHandler promptHandler) {
        super("logout", "Sign out");
        this.vault = vault;
        this.sessions = sessions;
        this.promptHandler = promptHandler;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    vault.lock();
                    sessions.invalidate(s.terminalId());
                    promptHandler.removeSession(s.terminalId());
                    s.output("Logged out.");
                    s.doneMeta().put(IpcMeta.CLEAR_SESSION, true);
                    return CommandResult.ALLOW;
                });
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/logout — Sign out");
    }
}
