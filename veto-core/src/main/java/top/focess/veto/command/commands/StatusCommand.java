package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVault;

public class StatusCommand implements CommandHandler {

    private final CredentialVault vault;

    public StatusCommand(CredentialVault vault) {
        this.vault = vault;
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show vault, session, and backend status";
    }

    @Override
    public String usage() {
        return "status";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of();
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        String user = vault.getCurrentUser();
        boolean unlocked = vault.isUnlocked();
        String displaySession =
                sessionToken != null
                        ? sessionToken.substring(0, Math.min(8, sessionToken.length())) + "..."
                        : "(none)";
        String content =
                String.format(
                        "Backend user: %s%nVault: %s%nSession: %s",
                        user != null ? user : "(none)",
                        unlocked ? "unlocked" : "locked",
                        displaySession);
        return new TerminalResponse(ResponseType.MESSAGE, content);
    }
}
