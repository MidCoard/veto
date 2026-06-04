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

public class SignupCommand extends VetoCommand {

    public SignupCommand(
            UserRegistry users,
            VaultKeyManager keys,
            CredentialVault vault,
            TerminalSessionManager sessions) {
        super("signup", "Create a new account");
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    if (users.anyUserExists()) {
                        tio.error("Already set up. Use /login.");
                        return refuse();
                    }
                    String u = args.get("user"), p = args.get("pass");

                    if (u == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT, "Choose a username:", Map.of()));
                        try {
                            u = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            u = null;
                        }
                        if (u == null || u.isBlank()) {
                            tio.error("Signup cancelled");
                            return refuse();
                        }
                    }
                    if (p == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT,
                                        "Choose a password:",
                                        Map.of("mask", true)));
                        try {
                            p = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            p = null;
                        }
                        if (p == null || p.isBlank()) {
                            tio.error("Signup cancelled");
                            return refuse();
                        }
                    }

                    var entity = users.create(u, p, UserRegistry.Role.ADMIN);
                    SecretKey mk = keys.deriveMasterKey(u, p, entity.getPasswordSalt());
                    SecretKey vk = keys.generateVaultKey();
                    keys.wrapVaultKey(vk, mk, u);
                    vault.unlock(vk, u);
                    String token = sessions.create(u);
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.MESSAGE,
                                    "Welcome, " + u + "!",
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
        return List.of("/signup [user] [pass]");
    }
}
