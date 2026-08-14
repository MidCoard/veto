package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.security.SignupMode;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.vault.*;

public class SignupCommand extends VetoCommand {

    private final @NonNull UserRegistry users;
    private final @NonNull AuthLifecycleManager authLifecycleManager;
    private final @NonNull SignupPolicy policy;

    public SignupCommand(
            @NonNull UserRegistry users,
            @NonNull AuthLifecycleManager authLifecycleManager,
            @NonNull SignupPolicy policy) {
        super("signup", "Create a new account");
        this.users = users;
        this.authLifecycleManager = authLifecycleManager;
        this.policy = policy;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    // Signup is an unauthenticated entry point. Once a session is logged in it must
                    // not create further accounts; the caller logs out first (or, in multi-user
                    // modes, an admin provisions the account via /user create).
                    if (s.isLoggedIn()) {
                        s.output(
                                "You are already logged in as "
                                        + s.requireUsername()
                                        + "; log out before signing up as a different user.");
                        return CommandResult.REFUSE;
                    }

                    // The first account is always the bootstrap admin (created in-app, any mode).
                    // After an admin exists, the signup mode governs further self-signup.
                    boolean bootstrap = users.adminCount() == 0;
                    SignupMode mode = policy.mode();
                    if (!bootstrap) {
                        switch (mode) {
                            case SOLO -> {
                                s.output("An account already exists - use /login.");
                                return CommandResult.REFUSE;
                            }
                            case INVITE -> {
                                s.output(
                                        "Self-signup is disabled; ask an administrator to create your"
                                                + " account.");
                                return CommandResult.REFUSE;
                            }
                            case PUBLIC -> {
                                // allowed; users after the bootstrap admin are USERs.
                            }
                        }
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

                    String role = bootstrap ? UserRegistry.Role.ADMIN : UserRegistry.Role.USER;
                    try {
                        users.create(u, p, role);
                    } catch (IllegalArgumentException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    try {
                        authLifecycleManager.signup(u, p);
                    } catch (Exception e) {
                        s.output("Account created but vault setup failed: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.setUsername(u);
                    s.output(
                            bootstrap
                                    ? "Administrator account created - welcome, " + u + "."
                                    : "Account created - welcome, " + u + ".");
                    return CommandResult.ALLOW;
                },
                opt("user"),
                opt("pass"));
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/signup [user] [pass] - Create a new account (password is prompted)");
    }
}
