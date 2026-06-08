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

public class SignupCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;
    private final TerminalSessionManager sessions;

    public SignupCommand(
            @NotNull UserRegistry users,
            @NotNull VaultKeyManager keys,
            @NotNull CredentialVault vault,
            @NotNull TerminalSessionManager sessions) {
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
                    if (s == null) return CommandResult.REFUSE;

                    if (users.anyUserExists()) {
                        s.output("An account already exists — use /login.");
                        return CommandResult.REFUSE;
                    }

                    String u = (String) args.get("user");
                    String p = (String) args.get("pass");

                    if (u == null) {
                        s.setNextPromptMeta(Map.of(IpcMeta.PROMPT, "Choose a username:"));
                        u = s.input();
                        if (u == null || u.isBlank()) {
                            s.output("Signup cancelled.");
                            return CommandResult.REFUSE;
                        }
                    }
                    if (p == null) {
                        s.setNextPromptMeta(
                                Map.of(IpcMeta.PROMPT, "Choose a password:", IpcMeta.MASK, true));
                        p = s.input();
                        if (p == null || p.isBlank()) {
                            s.output("Signup cancelled.");
                            return CommandResult.REFUSE;
                        }
                    }

                    var entity = users.create(u, p, UserRegistry.Role.ADMIN);
                    SecretKey mk = keys.deriveMasterKey(u, p, entity.getPasswordSalt());
                    SecretKey vk = keys.generateVaultKey();
                    keys.wrapVaultKey(vk, mk, u);
                    vault.unlock(vk, u);
                    sessions.create(s.terminalId(), u);
                    s.output("Account created — welcome, " + u + ".");
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
        return List.of("/signup [user] [pass] — Create a new account");
    }
}
