package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.vault.AuthException;
import top.focess.veto.vault.AuthService;

/** Terminal authentication adapter; passwords are always entered through masked input. */
public class LoginCommand extends VetoCommand {
    private final @NonNull AuthService auth;

    public LoginCommand(@NonNull AuthService auth) {
        super("login", "Sign in to your account");
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
                        s.output("Login cancelled.");
                        return CommandResult.REFUSE;
                    }
                    String password = s.input("Password:", true);
                    if (password == null) {
                        s.output("Login cancelled.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        var user = auth.loginTerminal(s, username, password);
                        s.output("Logged in as " + user.getUsername() + ".");
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
        return List.of("/login [user] - Sign in to your account (password is prompted)");
    }
}
