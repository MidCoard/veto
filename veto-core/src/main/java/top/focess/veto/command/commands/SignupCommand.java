package top.focess.veto.command.commands;

import java.util.List;
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.*;

public class SignupCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final AuthLifecycleManager authLifecycleManager;

    public SignupCommand(
            @NotNull UserRegistry users,
            @NotNull VaultKeyManager keys,
            @NotNull AuthLifecycleManager authLifecycleManager) {
        super("signup", "Create a new account");
        this.users = users;
        this.keys = keys;
        this.authLifecycleManager = authLifecycleManager;
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

                    String u = args.get("user");
                    String p = args.get("pass");

                    if (u == null) {
                        u = s.input("Choose a username:", false);
                        if (u.isEmpty()) {
                            s.output("Signup cancelled.");
                            return CommandResult.REFUSE;
                        }
                    }
                    if (p == null) {
                        p = s.input("Choose a password:", true);
                        if (p.isEmpty()) {
                            s.output("Signup cancelled.");
                            return CommandResult.REFUSE;
                        }
                    }

                    var entity = users.create(u, p, UserRegistry.Role.ADMIN);
                    SecretKey mk = keys.deriveMasterKey(u, p, entity.getPasswordSalt());
                    SecretKey vk = keys.generateVaultKey();
                    keys.wrapVaultKey(vk, mk, u);
                    authLifecycleManager.login(u, vk);
                    s.setUsername(u);
                    s.output("Account created — welcome, " + u + ".");
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
