package top.focess.veto.command.commands;

import top.focess.command.Command;
import top.focess.command.CommandResult;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVault;

public class LogoutCommand extends Command {

    public LogoutCommand(CredentialVault vault) {
        super("logout");
        addExecutor(
                (sender, args, io) -> {
                    vault.lock();
                    ((TerminalIO) io)
                            .respond(
                                    new TerminalResponse(
                                            ResponseType.MESSAGE,
                                            "Logged out.",
                                            java.util.Map.of("clearSession", true)));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public void init() {
    }

    @Override
    public java.util.List<String> usage(top.focess.command.CommandSender s) {
        return java.util.List.of("/logout");
    }
}
