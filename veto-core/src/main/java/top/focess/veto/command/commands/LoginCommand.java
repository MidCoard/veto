package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;

import top.focess.command.CommandSender;
import top.focess.command.InputTimeoutException;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.*;

public class LoginCommand extends VetoCommand {

    private final UserRegistry users;
    private final VaultKeyManager keys;
    private final CredentialVault vault;
    private final TerminalSessionManager sessions;

    public LoginCommand(
            UserRegistry users,
            VaultKeyManager keys,
            CredentialVault vault,
            TerminalSessionManager sessions) {
        super("login", "Sign in to your account");
        this.users = users;
        this.keys = keys;
        this.vault = vault;
        this.sessions = sessions;
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = args.get("user"), p = args.get("pass");

                    if (u == null) {
                        tio.respond(
                                new TerminalResponse(ResponseType.PROMPT, "Username:", Map.of()));
                        try {
                            u = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            u = null;
                        }
                        if (u == null || u.isBlank()) {
                            tio.error("Login cancelled");
                            return refuse();
                        }
                    }
                    if (p == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT, "Password:", Map.of("mask", true)));
                        try {
                            p = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            p = null;
                        }
                        if (p == null || p.isBlank()) {
                            tio.error("Login cancelled");
                            return refuse();
                        }
                    }

                    var userOpt = users.authenticate(u, p);
                    if (userOpt.isEmpty()) {
                        tio.error("Invalid username or password");
                        return refuse();
                    }
                    SecretKey mk = keys.deriveMasterKey(u, p, userOpt.get().getPasswordSalt());
                    SecretKey vk = keys.unwrapVaultKey(mk, u);
                    if (vk == null) {
                        tio.error("Failed to unlock");
                        return refuse();
                    }
                    vault.unlock(vk, u);
                    String token = sessions.create(u);
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.MESSAGE,
                                    "Welcome, " + u + ".",
                                    Map.of("username", u, "session", token)));
                    return allow();
                },
                opt("user"),
                opt("pass"));
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/login [user] [pass]");
    }
}
