package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;

import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVault;
import top.focess.veto.vault.UserRegistry;
import top.focess.veto.vault.VaultKeyManager;

public class LoginCommand implements CommandHandler {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;

    public LoginCommand(UserRegistry users, VaultKeyManager keys, CredentialVault vault) {
        this.users = users;
        this.keys = keys;
        this.vault = vault;
    }

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Authenticate and unlock vault";
    }

    @Override
    public String usage() {
        return "login <username> <password>";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of(
                new ArgDef("username", "string", true, "User name"),
                new ArgDef("password", "string", true, "Password"));
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        String username = (String) args.get("arg1");
        String password = (String) args.get("arg2");
        if (username == null || password == null)
            return TerminalResponse.error("Usage: login <username> <password>");

        var userOpt = users.authenticate(username, password);
        if (userOpt.isEmpty()) return TerminalResponse.error("Invalid username or password");

        SecretKey mk = keys.deriveMasterKey(username, password, userOpt.get().getPasswordSalt());
        SecretKey vk = keys.unwrapVaultKey(mk, username);
        if (vk == null) return TerminalResponse.error("Failed to unlock vault");

        vault.unlock(vk, username);
        return new TerminalResponse(
                ResponseType.MESSAGE,
                "Logged in as " + username + ". Vault unlocked.",
                Map.of("username", username));
    }
}
