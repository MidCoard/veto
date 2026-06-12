package top.focess.veto.command.commands;

import java.util.List;
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.contract.PromptMeta;
import top.focess.veto.vault.*;

public class LoginCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;

    public LoginCommand(
            @NotNull UserRegistry users,
            @NotNull VaultKeyManager keys,
            @NotNull CredentialVault vault) {
        super("login", "Sign in to your account");
        this.users = users;
        this.keys = keys;
        this.vault = vault;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    String u = (String) args.get("user");

                    if (u == null) {
                        s.setNextPromptMeta(PromptMeta.simple("Username:"));
                        u = s.input();
                        if (u == null || u.isEmpty()) {
                            s.output("Login cancelled.");
                            return CommandResult.REFUSE;
                        }
                    }
                    // Password is always prompted interactively with masking — never
                    // accepted as a command-line argument.
                    s.setNextPromptMeta(PromptMeta.masked("Password:"));
                    String p = s.input();
                    if (p == null || p.isEmpty()) {
                        s.output("Login cancelled.");
                        return CommandResult.REFUSE;
                    }

                    var userOpt = users.authenticate(u, p);
                    if (userOpt.isEmpty()) {
                        s.output("Invalid username or password.");
                        return CommandResult.REFUSE;
                    }

                    SecretKey mk = keys.deriveMasterKey(u, p, userOpt.get().getPasswordSalt());
                    SecretKey vk = keys.unwrapVaultKey(mk, u);
                    if (vk == null) {
                        s.output("Failed to unlock vault.");
                        return CommandResult.REFUSE;
                    }

                    vault.unlock(vk, u);
                    s.setUsername(u);
                    s.output("Logged in as " + u + ".");
                    s.doneMeta().put(IpcMeta.USERNAME, u);
                    s.doneMeta().put(IpcMeta.SESSION, s.terminalId());
                    return CommandResult.ALLOW;
                },
                opt("user"));
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/login [user] — Sign in to your account (password is prompted)");
    }
}
