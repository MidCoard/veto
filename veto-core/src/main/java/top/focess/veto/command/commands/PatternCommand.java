package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.focess.command.Command;
import top.focess.command.CommandCompletion;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

public class PatternCommand extends VetoCommand {

    private final CredentialVault vault;
    private final AgentPatternRepository repo;
    private final ConcurrentHashMap<String, String> active;

    public PatternCommand(
            CredentialVault v,
            AgentPatternRepository repo,
            ConcurrentHashMap<String, String> active) {
        super("pattern", "Manage agent patterns", "ap");
        this.vault = v;
        this.repo = repo;
        this.active = active;
        setExecutorPermission(LOGGED_IN);
    }

    @Override
    public void init() {
        var nameArg = arg("name").completer(this::completePatternName).description("Pattern name");

        // /pattern create
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    try {
                        String n = args.<String>get("name"),
                                p = args.<String>get("provider").toUpperCase();
                        String m = args.<String>get("model"), key = args.<String>get("apikey");
                        String sp =
                                args.<String>get("sysprompt") != null
                                        ? (String) args.<String>get("sysprompt")
                                        : "You are a helpful coding assistant. Be" + " concise.";
                        ProviderType.valueOf(p);
                        vault.store("pattern-" + n, key);
                        repo.save(
                                new AgentPatternEntity(
                                        n, p, m, "pattern-" + n, sp, s.getUsername()));
                        s.message("Pattern '" + n + "' created (" + p + "/" + m + ").");
                        return allow();
                    } catch (IllegalArgumentException e) {
                        s.error("Unknown provider");
                        return refuse();
                    } catch (Exception e) {
                        s.error(e.getMessage());
                        return refuse();
                    }
                },
                fixed("create").description("Create a new agent pattern"),
                arg("name"),
                arg("provider").description("LLM provider (e.g. DEEPSEEK, OPENAI)"),
                arg("model").description("Model name"),
                arg("apikey").description("API key for the provider"),
                opt("sysprompt").description("Custom system prompt"));

        // /pattern list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    var pats = repo.findByOwner(s.getUsername());
                    if (pats.isEmpty()) {
                        s.message("No patterns. /pattern create ...");
                        return allow();
                    }
                    String act = active.get(s.getUsername());
                    s.done(
                            Map.of(
                                    "headers",
                                    List.of("NAME", "PROVIDER", "MODEL", "ACTIVE"),
                                    "rows",
                                    pats.stream()
                                            .map(
                                                    p ->
                                                            List.of(
                                                                    p.getName()
                                                                            + (p.getName()
                                                                                            .equals(
                                                                                                    act)
                                                                                    ? " *"
                                                                                    : ""),
                                                                    p.getProvider(),
                                                                    p.getModel(),
                                                                    p.getName().equals(act)
                                                                            ? "✓"
                                                                            : ""))
                                            .toList()));
                    return allow();
                },
                fixed("list").description("List your patterns"));

        // /pattern use
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    String n = args.get("name");
                    var f =
                            repo.findByOwner(s.getUsername()).stream()
                                    .filter(p -> p.getName().equals(n))
                                    .findFirst();
                    if (f.isEmpty()) {
                        s.error("Pattern not found: " + n);
                        return refuse();
                    }
                    active.put(s.getUsername(), n);
                    s.message(
                            "Using '"
                                    + n
                                    + "' ("
                                    + f.get().getProvider()
                                    + "/"
                                    + f.get().getModel()
                                    + ").");
                    return allow();
                },
                fixed("use").description("Activate a pattern"),
                nameArg);

        // /pattern delete
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    String n = args.get("name");
                    var pats = repo.findByOwner(s.getUsername());
                    if (pats.stream().noneMatch(p -> p.getName().equals(n))) {
                        s.error("Pattern not found: " + n);
                        return refuse();
                    }
                    repo.deleteByNameAndOwner(n, s.getUsername());
                    if (n.equals(active.get(s.getUsername()))) {
                        active.remove(s.getUsername());
                    }
                    try {
                        vault.delete("pattern-" + n);
                    } catch (Exception ign) {
                    }
                    s.message("Pattern '" + n + "' deleted.");
                    return allow();
                },
                fixed("delete").description("Delete a pattern"),
                nameArg);

        // /pattern show
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    String n = args.get("name");
                    var f =
                            repo.findByOwner(s.getUsername()).stream()
                                    .filter(p -> p.getName().equals(n))
                                    .findFirst();
                    if (f.isEmpty()) {
                        s.error("Pattern not found: " + n);
                        return refuse();
                    }
                    var p = f.get();
                    s.done(
                            Map.of(
                                    "headers",
                                    List.of("Property", "Value"),
                                    "rows",
                                    List.of(
                                            List.of("Name", p.getName()),
                                            List.of("Provider", p.getProvider()),
                                            List.of("Model", p.getModel()),
                                            List.of("System Prompt", p.getSystemPrompt()),
                                            List.of(
                                                    "Active",
                                                    p.getName().equals(active.get(s.getUsername()))
                                                            ? "yes"
                                                            : "no"))));
                    return allow();
                },
                fixed("show").description("Show pattern details"),
                nameArg);
    }

    private List<CommandCompletion> completePatternName(
            CommandSender sender, Command cmd, String[] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String u = ((VetoCommandSender) sender).getUsername();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return repo.findByOwner(u).stream()
                .filter(p -> p.getName().toLowerCase().startsWith(prefix))
                .map(p -> CommandCompletion.of(p.getName(), p.getProvider() + " / " + p.getModel()))
                .toList();
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of(
                "/pattern create <name> <provider> <model> <apikey> [sysprompt] — Create a new"
                        + " agent pattern",
                "/pattern list — List your patterns",
                "/pattern use <name> — Activate a pattern",
                "/pattern delete <name> — Delete a pattern",
                "/pattern show <name> — Show pattern details");
    }
}
