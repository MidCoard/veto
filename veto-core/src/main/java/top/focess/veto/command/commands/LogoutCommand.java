package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.LogoutException;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.AuthLifecycleManager;

public class LogoutCommand extends VetoCommand {

    private final AuthLifecycleManager authLifecycleManager;
    private final PromptHandler promptHandler;

    public LogoutCommand(
            @NonNull AuthLifecycleManager authLifecycleManager,
            @NonNull PromptHandler promptHandler) {
        super("logout", "Sign out");
        this.authLifecycleManager = authLifecycleManager;
        this.promptHandler = promptHandler;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    String user = s.username();
                    if (user != null) {
                        authLifecycleManager.logout(user);
                    }
                    s.setUsername(null);
                    promptHandler.removeSession(s.terminalId());
                    s.output("Logged out.");
                    throw new LogoutException();
                });
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/logout — Sign out");
    }
}
