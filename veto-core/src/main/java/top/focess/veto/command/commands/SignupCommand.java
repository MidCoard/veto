package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.*;

public class SignupCommand extends VetoCommand {

    private final UserRegistry users;
    private final AuthLifecycleManager authLifecycleManager;

    public SignupCommand(
            @NonNull UserRegistry users, @NonNull AuthLifecycleManager authLifecycleManager) {
        super("signup", "Create a new account");
        this.users = users;
        this.authLifecycleManager = authLifecycleManager;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    if (users.anyUserExists()) {
                        s.output("An account already exists - use /login.");
                        return CommandResult.REFUSE;
                    }

                    String u = args.get("user");
                    String p = args.get("pass");

                    if (u == null) {
                        u = s.input("Choose a username:", false);
                        if (u == null) {
                            s.output("Signup cancelled.");
                            return CommandResult.REFUSE;
                        }
                        if (u.isEmpty()) {
                            s.output("Username cannot be empty.");
                            return CommandResult.REFUSE;
                        }
                    }
                    if (p == null) {
                        p = s.input("Choose a password:", true);
                        if (p == null) {
                            s.output("Signup cancelled.");
                            return CommandResult.REFUSE;
                        }
                        if (p.isEmpty()) {
                            s.output("Password cannot be empty.");
                            return CommandResult.REFUSE;
                        }
                    }

                    users.create(u, p, UserRegistry.Role.ADMIN);
                    try {
                        authLifecycleManager.signup(u, p);
                    } catch (Exception e) {
                        s.output("Account created but vault setup failed: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.setUsername(u);
                    s.output("Account created - welcome, " + u + ".");
                    return CommandResult.ALLOW;
                },
                opt("user"),
                opt("pass"));
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/signup [user] [pass] - Create a new account");
    }
}
