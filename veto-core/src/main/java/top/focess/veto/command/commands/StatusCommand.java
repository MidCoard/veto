package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVault;

public class StatusCommand extends VetoCommand {

    public StatusCommand(CredentialVault vault, PromptHandler ph) {
        super("status", "Show session info");
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String user = vault.getCurrentUser();
                    if (user == null) {
                        tio.error("Not logged in.");
                        return refuse();
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
                                            List.of("", ""),
                                            "rows",
                                            List.of(
                                                    List.of("User", user),
                                                    List.of("Sessions", String.valueOf(sessions)),
                                                    List.of(
                                                            "Total turns",
                                                            String.valueOf(turns))))));
                    return allow();
                });
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/status");
    }
}
