package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.*;

public class LoginCommand extends VetoCommand {

    private final @NonNull UserRegistry users;
    private final @NonNull AuthLifecycleManager authLifecycleManager;

    public LoginCommand(
            @NonNull UserRegistry users, @NonNull AuthLifecycleManager authLifecycleManager) {
        super("login", "Sign in to your account");
        this.users = users;
        this.authLifecycleManager = authLifecycleManager;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    String u = args.get("user");

                    if (u == null) {
                        u = s.input("Username:", false);
                        if (u == null) {
                            s.output("Login cancelled.");
                            return CommandResult.REFUSE;
                        }
                        if (u.isEmpty()) {
                            s.output("Username cannot be empty.");
                            return CommandResult.REFUSE;
                        }
                    }
                    // Password is always prompted interactively with masking - never
                    // accepted as a command-line argument.
                    String p = s.input("Password:", true);
                    if (p == null) {
                        s.output("Login cancelled.");
                        return CommandResult.REFUSE;
                    }
                    if (p.isEmpty()) {
                        s.output("Password cannot be empty.");
                        return CommandResult.REFUSE;
                    }

                    if (users.authenticate(u, p).isEmpty()) {
                        s.output("Invalid username or password.");
                        return CommandResult.REFUSE;
                    }

                    try {
                        authLifecycleManager.login(u, p);
                    } catch (Exception e) {
                        s.output("Failed to unlock vault: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.setUsername(u);
                    s.output("Logged in as " + u + ".");
                    return CommandResult.ALLOW;
                },
                opt("user"));
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/login [user] - Sign in to your account (password is prompted)");
    }
}
