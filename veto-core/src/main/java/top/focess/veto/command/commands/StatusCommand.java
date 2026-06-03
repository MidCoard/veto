package top.focess.veto.command.commands;

import java.util.Map;

import top.focess.command.Command;
import top.focess.command.CommandResult;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVault;

public class StatusCommand extends Command {

    public StatusCommand(CredentialVault vault, PromptHandler ph) {
        super("status");
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String user = vault.getCurrentUser();
                    if (user == null) {
                        tio.error("Not logged in. Use /login.");
                        return CommandResult.REFUSE;
                    }
                    int sessions = ph.sessions().size();
                    int turns =
                            ph.sessions().values().stream().mapToInt(a -> a.turns().size()).sum();
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.TABLE,
                                    "",
                                    Map.of(
                                            "headers",
                                            java.util.List.of("", ""),
                                            "rows",
                                            java.util.List.of(
                                                    java.util.List.of("User", user),
                                                    java.util.List.of(
                                                            "Sessions", String.valueOf(sessions)),
                                                    java.util.List.of(
                                                            "Total turns",
                                                            String.valueOf(turns))))));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public void init() {
    }

    @Override
    public java.util.List<String> usage(top.focess.command.CommandSender s) {
        return java.util.List.of("/status");
    }
}
