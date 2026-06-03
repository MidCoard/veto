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

public class SignupCommand implements CommandHandler {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;

    public SignupCommand(UserRegistry users, VaultKeyManager keys, CredentialVault vault) {
        this.users = users;
        this.keys = keys;
        this.vault = vault;
    }

    @Override
    public String name() {
        return "signup";
    }

    @Override
    public String description() {
        return "Create account (first-run only)";
    }

    @Override
    public String usage() {
        return "signup <username> <password>";
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
            return TerminalResponse.error("Usage: signup <username> <password>");

        if (users.anyUserExists())
            return TerminalResponse.error("Vault already set up. Use login instead.");

        var entity = users.create(username, password, UserRegistry.Role.ADMIN);
        SecretKey mk = keys.deriveMasterKey(username, password, entity.getPasswordSalt());
        SecretKey vk = keys.generateVaultKey();
        keys.wrapVaultKey(vk, mk, username);
        vault.unlock(vk, username);

        return new TerminalResponse(
                ResponseType.MESSAGE,
                "Account created. Welcome, " + username + "!",
                Map.of("username", username));
    }
}
