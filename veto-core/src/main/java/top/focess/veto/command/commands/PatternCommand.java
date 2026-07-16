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
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

public class PatternCommand extends VetoCommand {

    private static final Logger log = LoggerFactory.getLogger(PatternCommand.class);

    private final CredentialVault vault;
    private final AgentPatternRepository repo;

    public PatternCommand(@NonNull CredentialVault v, @NonNull AgentPatternRepository repo) {
        super("pattern", "Manage agent patterns", "ap");
        this.vault = v;
        this.repo = repo;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var nameArg = arg("name").completer(this::completePatternName).description("Pattern name");

        // /pattern create <name> <provider> <model> [sysprompt]
        // API key is prompted interactively with masked input — never in the clear.
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    try {
                        String n = args.get("name");
                        String provider = args.get("provider");
                        provider = provider.toUpperCase();
                        String model = args.get("model");
                        String sp =
                                args.getOrDefault(
                                        "sysprompt",
                                        "You are a helpful coding assistant. Be concise.");
                        ProviderType.valueOf(provider);

                        String key = s.input("API Key for " + provider + ":", true);
                        if (key == null) {
                            s.output("Pattern creation cancelled.");
                            return CommandResult.REFUSE;
                        }
                        if (key.isEmpty()) {
                            s.output("API key cannot be empty.");
                            return CommandResult.REFUSE;
                        }

                        AgentPatternEntity entity =
                                new AgentPatternEntity(
                                        n, provider, model, "pattern-" + n, sp, s.username());
                        repo.save(entity);
                        try {
                            vault.store("pattern-" + n, key);
                        } catch (Exception e) {
                            repo.delete(entity);
                            throw e;
                        }
                        s.output("Pattern '" + n + "' created (" + provider + "/" + model + ").");
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException e) {
                        s.output("Unknown provider: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                fixed("create").description("Create a new agent pattern"),
                arg("name"),
                arg("provider").description("LLM provider (e.g. DEEPSEEK, OPENAI)"),
                arg("model").description("Model name"),
                opt("sysprompt").description("Custom system prompt"));

        // /pattern list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    var pats = repo.findByOwner(s.username());
                    if (pats.isEmpty()) {
                        s.output("No patterns configured. Use /pattern create ...");
                        return CommandResult.ALLOW;
                    }
                    s.output("Patterns:");
                    for (var p : pats) {
                        s.output(
                                String.format(
                                        "  %-16s %-12s %s",
                                        p.getName(), p.getProvider(), p.getModel()));
                    }
                    return CommandResult.ALLOW;
                },
                fixed("list").description("List your patterns"));

        // /pattern delete <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String n = args.get("name");
                    var pats = repo.findByOwner(s.username());
                    if (pats.stream().noneMatch(p -> p.getName().equals(n))) {
                        s.output("Pattern not found: " + n);
                        return CommandResult.REFUSE;
                    }
                    repo.deleteByNameAndOwner(n, s.username());
                    try {
                        vault.delete("pattern-" + n);
                    } catch (Exception ignored) {
                    }
                    s.output("Pattern '" + n + "' deleted.");
                    return CommandResult.ALLOW;
                },
                fixed("delete").description("Delete a pattern"),
                nameArg);

        // /pattern show <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String n = args.get("name");
                    var found =
                            repo.findByOwner(s.username()).stream()
                                    .filter(p -> p.getName().equals(n))
                                    .findFirst();
                    if (found.isEmpty()) {
                        s.output("Pattern not found: " + n);
                        return CommandResult.REFUSE;
                    }
                    var p = found.get();
                    s.output("Pattern: " + p.getName());
                    s.output("  Provider:      " + p.getProvider());
                    s.output("  Model:         " + p.getModel());
                    s.output("  System Prompt: " + p.getSystemPrompt());
                    return CommandResult.ALLOW;
                },
                fixed("show").description("Show pattern details"),
                nameArg);
    }

    private @NonNull List<CommandCompletion> completePatternName(
            @NonNull CommandSender sender, @NonNull Command cmd, @NonNull String[] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String u = ((VetoCommandSender) sender).username();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return repo.findByOwner(u).stream()
                .filter(p -> p.getName().toLowerCase().startsWith(prefix))
                .map(p -> CommandCompletion.of(p.getName(), p.getProvider() + " / " + p.getModel()))
                .toList();
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        log.info("PatternCommand.usage() called");
        return List.of(
                "/pattern create <name> <provider> <model> [sysprompt] — Create a pattern (API key"
                        + " is prompted)",
                "/pattern list — List your patterns",
                "/pattern delete <name> — Delete a pattern",
                "/pattern show <name> — Show pattern details");
    }
}
