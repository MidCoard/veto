package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.*;

public class LoginCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;
    private final TerminalSessionManager sessions;

    public LoginCommand(
            UserRegistry users,
            VaultKeyManager keys,
            CredentialVault vault,
            TerminalSessionManager sessions) {
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
                    String u = args.get("user"), p = args.get("pass");

                    if (u == null) {
                        u = s.prompt("Username:", Map.of(), 60_000);
                        if (u == null || u.isBlank()) {
                            s.error("Login cancelled");
                            return refuse();
                        }
                    }
                    if (p == null) {
                        p = s.prompt("Password:", Map.of("mask", true), 60_000);
                        if (p == null || p.isBlank()) {
                            s.error("Login cancelled");
                            return refuse();
                        }
                    }

                    var userOpt = users.authenticate(u, p);
                    if (userOpt.isEmpty()) {
                        s.error("Invalid username or password");
                        return refuse();
                    }
                    SecretKey mk = keys.deriveMasterKey(u, p, userOpt.get().getPasswordSalt());
                    SecretKey vk = keys.unwrapVaultKey(mk, u);
                    if (vk == null) {
                        s.error("Failed to unlock");
                        return refuse();
                    }
                    vault.unlock(vk, u);
                    sessions.create(s.terminalId(), u);
                    s.done(Map.of("username", u, "session", s.terminalId()));
                    return allow();
                },
                opt("user"),
                opt("pass"));
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/login [user] [pass] — Sign in to your account");
    }
}
