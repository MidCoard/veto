package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVault;

public class LogoutCommand implements CommandHandler {

    private final CredentialVault vault;

    public LogoutCommand(CredentialVault vault) {
        this.vault = vault;
    }

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public String description() {
        return "End session and lock vault";
    }

    @Override
    public String usage() {
        return "logout";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of();
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        vault.lock();
        return new TerminalResponse(
                ResponseType.MESSAGE, "Logged out. Vault locked.", Map.of("clearSession", true));
    }
}
