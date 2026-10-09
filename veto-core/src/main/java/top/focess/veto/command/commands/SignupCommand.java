package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.vault.AuthException;
import top.focess.veto.vault.AuthService;
import top.focess.veto.vault.UserRegistry;

/** Terminal authentication adapter; passwords are always entered through masked input. */
public class SignupCommand extends VetoCommand {
    private final @NonNull AuthService auth;

    public SignupCommand(@NonNull AuthService auth) {
        super("signup", "Create a new account");
        this.auth = auth;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    var s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String username = args.get("user");
                    if (username == null) username = s.input("Username:", false);
                    if (username == null) {
                        s.output("Signup cancelled.");
                        return CommandResult.REFUSE;
                    }
                    String password = s.input("Choose a password:", true);
                    if (password == null) {
                        s.output("Signup cancelled.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        var user = auth.signupTerminal(s, username, password);
                        s.output(
                                (UserRegistry.Role.ADMIN.equals(user.getRole())
                                                ? "Administrator account created - welcome, "
                                                : "Account created - welcome, ")
                                        + user.getUsername()
                                        + ".");
                        return CommandResult.ALLOW;
                    } catch (AuthException rejected) {
                        s.output(rejected.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                opt("user"));
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender sender) {
        return List.of("/signup [user] - Create a new account (password is prompted)");
    }
}
