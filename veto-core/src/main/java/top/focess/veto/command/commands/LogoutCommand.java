package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.LogoutException;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.AuthException;
import top.focess.veto.vault.AuthService;

/**
 * Signs the current user out, closes their vault, and detaches the terminal's active session
 * ({@code /logout}).
 */
public class LogoutCommand extends VetoCommand {

    private final @NonNull AuthService auth;

    public LogoutCommand(@NonNull AuthService auth) {
        super("logout", "Sign out");
        this.auth = auth;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    try {
                        auth.logoutTerminal(s, s.requireUserId());
                    } catch (AuthException rejected) {
                        s.output(rejected.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("Logged out.");
                    throw new LogoutException();
                });
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/logout — Sign out");
    }
}
