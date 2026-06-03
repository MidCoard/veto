package top.focess.veto.command.commands;

import javax.crypto.SecretKey;

import top.focess.command.*;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.*;

public class SignupCommand extends Command {

    public SignupCommand(UserRegistry users, VaultKeyManager keys, CredentialVault vault) {
        super("signup");
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    if (users.anyUserExists()) {
                        tio.error("Already set up. Use /login.");
                        return CommandResult.REFUSE;
                    }
                    String u = args.get("user"), p = args.get("pass");

                    if (u == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT,
                                        "Choose a username:",
                                        java.util.Map.of()));
                        try {
                            u = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            u = null;
                        }
                        if (u == null || u.isBlank()) {
                            tio.error("Signup cancelled");
                            return CommandResult.REFUSE;
                        }
                    }
                    if (p == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT,
                                        "Choose a password:",
                                        java.util.Map.of("mask", true)));
                        try {
                            p = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            p = null;
                        }
                        if (p == null || p.isBlank()) {
                            tio.error("Signup cancelled");
                            return CommandResult.REFUSE;
                        }
                    }

                    var entity = users.create(u, p, UserRegistry.Role.ADMIN);
                    SecretKey mk = keys.deriveMasterKey(u, p, entity.getPasswordSalt());
                    SecretKey vk = keys.generateVaultKey();
                    keys.wrapVaultKey(vk, mk, u);
                    vault.unlock(vk, u);
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.MESSAGE,
                                    "Welcome, " + u + "!",
                                    java.util.Map.of("username", u)));
                    return CommandResult.ALLOW;
                },
                CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER).named("user"),
                CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER).named("pass"));
    }

    @Override
    public void init() {
    }

    @Override
    public java.util.List<String> usage(top.focess.command.CommandSender s) {
        return java.util.List.of("/signup [user] [pass]");
    }
}
