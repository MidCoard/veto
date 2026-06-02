package top.focess.veto.terminal.command;

import java.util.List;
import javax.crypto.SecretKey;

import top.focess.command.*;
import top.focess.veto.terminal.TerminalContext;

public class LoginCommand extends Command {

    private final TerminalContext ctx;

    public LoginCommand(TerminalContext ctx) {
        super("login");
        this.ctx = ctx;
    }

    @Override
    public void init() {
        addExecutor(
                (s, d, io) -> {
                    String username = d.get("username");
                    String password = d.get("password");
                    var user = ctx.users.authenticate(username, password);
                    if (user.isEmpty()) {
                        io.output("Invalid username or password.");
                        return CommandResult.ALLOW;
                    }
                    SecretKey mk =
                            ctx.keys.deriveMasterKey(
                                    username, password, user.get().getPasswordSalt());
                    SecretKey vk = ctx.keys.unwrapVaultKey(mk, username);
                    if (vk == null) {
                        io.output("Failed to unlock vault.");
                        return CommandResult.ALLOW;
                    }
                    ctx.vault.unlock(vk, username);
                    ctx.sender.authenticate(username);
                    io.output("Logged in as " + username + ". Vault unlocked.");
                    return CommandResult.ALLOW;
                },
                CommandArgument.of(DataConverter.DEFAULT_DATA_CONVERTER).named("username"),
                CommandArgument.of(DataConverter.DEFAULT_DATA_CONVERTER).named("password"));
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/login <username> <password>");
    }
}
