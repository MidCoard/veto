package top.focess.veto.terminal.command;

import java.util.List;
import javax.crypto.SecretKey;

import top.focess.command.*;
import top.focess.veto.terminal.TerminalContext;
import top.focess.veto.vault.UserRegistry;

public class SignupCommand extends Command {

    private final TerminalContext ctx;

    public SignupCommand(TerminalContext ctx) {
        super("signup", "register");
        this.ctx = ctx;
    }

    @Override
    public void init() {
        addExecutor(
                (s, d, io) -> {
                    String username = d.get("username");
                    String password = d.get("password");
                    if (ctx.users.anyUserExists()) {
                        io.output("Vault already set up. Use /login.");
                        return CommandResult.ALLOW;
                    }
                    if (password.length() < 8) {
                        io.output("Password must be at least 8 characters.");
                        return CommandResult.ALLOW;
                    }
                    var entity = ctx.users.create(username, password, UserRegistry.Role.ADMIN);
                    SecretKey mk =
                            ctx.keys.deriveMasterKey(username, password, entity.getPasswordSalt());
                    SecretKey vk = ctx.keys.generateVaultKey();
                    ctx.keys.wrapVaultKey(vk, mk, username);
                    ctx.vault.unlock(vk, username);
                    ctx.sender.authenticate(username);
                    io.output("Account created. Welcome, " + username + "!");
                    return CommandResult.ALLOW;
                },
                CommandArgument.of(DataConverter.DEFAULT_DATA_CONVERTER).named("username"),
                CommandArgument.of(DataConverter.DEFAULT_DATA_CONVERTER).named("password"));
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/signup <username> <password>");
    }
}
