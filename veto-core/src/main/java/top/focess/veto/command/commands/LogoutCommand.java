package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.CredentialVault;

public class LogoutCommand extends VetoCommand {

    private final CredentialVault vault;
    private final TerminalSessionManager sessions;
    private final PromptHandler promptHandler;

    public LogoutCommand(
            CredentialVault vault, TerminalSessionManager sessions, PromptHandler promptHandler) {
        super("logout", "Sign out");
        this.vault = vault;
        this.sessions = sessions;
        this.promptHandler = promptHandler;
        setExecutorPermission(LOGGED_IN);
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    vault.lock();
                    sessions.invalidateAll(s.getUsername());
                    sessions.invalidate(s.terminalId());
                    promptHandler.removeSession(s.terminalId());
                    s.done(Map.of("clearSession", true));
                    return allow();
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/logout — Sign out");
    }
}
