package top.focess.veto.command.commands;

import javax.crypto.SecretKey;

import top.focess.command.*;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.*;

public class LoginCommand extends Command {

    public LoginCommand(UserRegistry users, VaultKeyManager keys, CredentialVault vault) {
        super("login");
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = args.get("user");
                    String p = args.get("pass");

                    if (u == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT, "Username:", java.util.Map.of()));
                        try {
                            u = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            u = null;
                        }
                        if (u == null || u.isBlank()) {
                            tio.error("Login cancelled");
                            return CommandResult.REFUSE;
                        }
                    }
                    if (p == null) {
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.PROMPT,
                                        "Password:",
                                        java.util.Map.of("mask", true)));
                        try {
                            p = tio.input(60_000);
                        } catch (InputTimeoutException e) {
                            p = null;
                        }
                        if (p == null || p.isBlank()) {
                            tio.error("Login cancelled");
                            return CommandResult.REFUSE;
                        }
                    }

                    var userOpt = users.authenticate(u, p);
                    if (userOpt.isEmpty()) {
                        tio.error("Invalid username or password");
                        return CommandResult.REFUSE;
                    }
                    SecretKey mk = keys.deriveMasterKey(u, p, userOpt.get().getPasswordSalt());
                    SecretKey vk = keys.unwrapVaultKey(mk, u);
                    if (vk == null) {
                        tio.error("Failed to unlock");
                        return CommandResult.REFUSE;
                    }
                    vault.unlock(vk, u);
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.MESSAGE,
                                    "Welcome, " + u + ".",
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
        return java.util.List.of("/login [user] [pass]");
    }
}
