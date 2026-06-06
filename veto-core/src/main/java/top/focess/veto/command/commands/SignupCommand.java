package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.*;

public class SignupCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;
    private final TerminalSessionManager sessions;

    public SignupCommand(
            UserRegistry users,
            VaultKeyManager keys,
            CredentialVault vault,
            TerminalSessionManager sessions) {
        super("signup", "Create a new account");
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
                    if (users.anyUserExists()) {
                        s.error("Already set up. Use /login.");
                        return refuse();
                    }
                    String u = args.get("user"), p = args.get("pass");

                    if (u == null) {
                        u = s.prompt("Choose a username:", Map.of(), 60_000);
                        if (u == null || u.isBlank()) {
                            s.error("Signup cancelled");
                            return refuse();
                        }
                    }
                    if (p == null) {
                        p = s.prompt("Choose a password:", Map.of("mask", true), 60_000);
                        if (p == null || p.isBlank()) {
                            s.error("Signup cancelled");
                            return refuse();
                        }
                    }

                    var entity = users.create(u, p, UserRegistry.Role.ADMIN);
                    SecretKey mk = keys.deriveMasterKey(u, p, entity.getPasswordSalt());
                    SecretKey vk = keys.generateVaultKey();
                    keys.wrapVaultKey(vk, mk, u);
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
        return List.of("/signup [user] [pass] — Create a new account");
    }
}
