package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
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
        addExecutor(
                (sender, args, io) -> {
                    vault.lock();
                    if (sender instanceof top.focess.veto.command.VetoCommandSender vs
                            && vs.isLoggedIn()) {
                        sessions.invalidateAll(vs.getUsername());
                    }
                    ((TerminalIO) io)
                            .respond(
                                    new TerminalResponse(
                                            ResponseType.MESSAGE,
                                            "Logged out.",
                                            Map.of("clearSession", true)));
                    return allow();
                });
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/logout");
    }
}
