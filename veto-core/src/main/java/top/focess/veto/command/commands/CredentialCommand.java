package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.Command;
import top.focess.command.CommandCompletion;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.KeysteadVault;

/**
 * Manages per-user credential secrets keyed by name. A model-tier configuration names a {@code
 * credential-key} per tier (e.g. {@code deepseek-default}); the user stores the actual secret for
 * that key here, in their per-user vault. Patterns and agents reference tiers, never secrets - this
 * command is the single way a user provisions an API key for a tier.
 */
public class CredentialCommand extends VetoCommand {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.command.commands.CredentialCommand");

    private final @NonNull KeysteadVault vault;

    public CredentialCommand(@NonNull KeysteadVault vault) {
        super("credential", "Manage per-user API-key credentials", "cred");
        this.vault = vault;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var keyArg = arg("key").completer(this::completeKey).description("Credential key");

        // /credential set <key>
        // The secret is prompted with masked input - never in the clear.
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String key = args.get("key");
                    if (key == null || key.isBlank()) return CommandResult.REFUSE;
                    String secret = s.input("Secret for " + key + ":", true);
                    if (secret == null) {
                        s.output("Credential set cancelled.");
                        return CommandResult.REFUSE;
                    }
                    if (secret.isEmpty()) {
                        s.output("Secret cannot be empty.");
                        return CommandResult.REFUSE;
                    }
                    vault.saveNote(key, secret);
                    s.output("Credential '" + key + "' stored.");
                    return CommandResult.ALLOW;
                },
                fixed("set").description("Store (upsert) a credential secret"),
                arg("key"));

        // /credential list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    var titles = vault.listTitles();
                    if (titles.isEmpty()) {
                        s.output("No credentials stored. Use /credential set <key> ...");
                        return CommandResult.ALLOW;
                    }
                    s.output("Credentials:");
                    for (var t : titles) {
                        s.output("  " + t);
                    }
                    return CommandResult.ALLOW;
                },
                fixed("list").description("List credential key names"));

        // /credential delete <key>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String key = args.get("key");
                    if (key == null || key.isBlank()) return CommandResult.REFUSE;
                    if (vault.deleteNote(key)) {
                        s.output("Credential '" + key + "' deleted.");
                    } else {
                        s.output("Credential not found: " + key);
                    }
                    return CommandResult.ALLOW;
                },
                fixed("delete").description("Delete a credential"),
                keyArg);
    }

    private @NonNull List<CommandCompletion> completeKey(
            @NonNull CommandSender sender, @NonNull Command cmd, @NonNull String @NonNull [] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return vault.listTitles().stream()
                .filter(t -> t.toLowerCase().startsWith(prefix))
                .map(t -> CommandCompletion.of(t, "credential key"))
                .toList();
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        log.info("CredentialCommand.usage() called");
        return List.of(
                "/credential set <key> - Store a credential secret (prompted, masked)",
                "/credential list - List credential key names",
                "/credential delete <key> - Delete a credential");
    }
}
