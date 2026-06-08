package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.vault.*;

public class LoginCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;
    private final TerminalSessionManager sessions;

    public LoginCommand(
            @NotNull UserRegistry users,
            @NotNull VaultKeyManager keys,
            @NotNull CredentialVault vault,
            @NotNull TerminalSessionManager sessions) {
        super("login", "Sign in to your account");
        this.users = users;
        this.keys = keys;
        this.vault = vault;
        this.sessions = sessions;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    String u = (String) args.get("user");
                    String p = (String) args.get("pass");

                    if (u == null) {
                        s.setNextPromptMeta(Map.of(IpcMeta.PROMPT, "Username:"));
                        u = s.input();
                        if (u == null || u.isBlank()) {
                            s.output("Login cancelled.");
                            return CommandResult.REFUSE;
                        }
                    }
                    if (p == null) {
                        s.setNextPromptMeta(
                                Map.of(IpcMeta.PROMPT, "Password:", IpcMeta.MASK, true));
                        p = s.input();
                        if (p == null || p.isBlank()) {
                            s.output("Login cancelled.");
                            return CommandResult.REFUSE;
                        }
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
                    sessions.create(s.terminalId(), u);
                    s.output("Logged in as " + u + ".");
                    s.doneMeta().put(IpcMeta.USERNAME, u);
                    s.doneMeta().put(IpcMeta.SESSION, s.terminalId());
                    return CommandResult.ALLOW;
                },
                opt("user"),
                opt("pass"));
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/login [user] [pass] — Sign in to your account");
    }
}
