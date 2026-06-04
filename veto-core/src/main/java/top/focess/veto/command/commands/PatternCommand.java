package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.focess.command.Command;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
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

        var nameArg = arg("name").completer(this::completePatternName);

        // /pattern create <name> <provider> <model> <apikey> [sysprompt]
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = user(sender, tio);
                    if (u == null) return refuse();
                    try {
                        String n = args.<String>get("name"),
                                p = args.<String>get("provider").toUpperCase();
                        String m = args.<String>get("model"), key = args.<String>get("apikey");
                        String sp =
                                args.<String>get("sysprompt") != null
                                        ? (String) args.<String>get("sysprompt")
                                        : "You are a helpful coding assistant. Be concise.";
                        ProviderType.valueOf(p);
                        vault.store("pattern-" + n, key);
                        repo.save(new AgentPatternEntity(n, p, m, "pattern-" + n, sp, u));
                        tio.message("Pattern '" + n + "' created (" + p + "/" + m + ").");
                        return allow();
                    } catch (IllegalArgumentException e) {
                        tio.error("Unknown provider");
                        return refuse();
                    } catch (Exception e) {
                        tio.error(e.getMessage());
                        return refuse();
                    }
                },
                fixed("create"),
                arg("name"),
                arg("provider"),
                arg("model"),
                arg("apikey"),
                opt("sysprompt"));

        // /pattern list
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = user(sender, tio);
                    if (u == null) return refuse();
                    var pats = repo.findByOwner(u);
                    if (pats.isEmpty()) {
                        tio.message("No patterns. /pattern create ...");
                        return allow();
                    }
                    String act = active.get(u);
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.TABLE,
                                    "",
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
                                                                            p.getName()
                                                                                    .equals(
                                                                                            act)
                                                                                    ? "✓"
                                                                                    : ""))
                                                    .toList())));
                    return allow();
                },
                fixed("list"));

        // /pattern use <name>
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = user(sender, tio);
                    if (u == null) return refuse();
                    String n = args.get("name");
                    var f =
                            repo.findByOwner(u).stream()
                                    .filter(p -> p.getName().equals(n))
                                    .findFirst();
                    if (f.isEmpty()) {
                        tio.error("Pattern not found: " + n);
                        return refuse();
                    }
                    active.put(u, n);
                    tio.message(
                            "Using '"
                                    + n
                                    + "' ("
                                    + f.get().getProvider()
                                    + "/"
                                    + f.get().getModel()
                                    + ").");
                    return allow();
                },
                fixed("use"),
                nameArg);

        // /pattern delete <name>
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = user(sender, tio);
                    if (u == null) return refuse();
                    String n = args.get("name");
                    var pats = repo.findByOwner(u);
                    if (pats.stream().noneMatch(p -> p.getName().equals(n))) {
                        tio.error("Pattern not found: " + n);
                        return refuse();
                    }
                    repo.deleteByNameAndOwner(n, u);
                    if (n.equals(active.get(u))) active.remove(u);
                    try {
                        vault.delete("pattern-" + n);
                    } catch (Exception ign) {
                    }
                    tio.message("Pattern '" + n + "' deleted.");
                    return allow();
                },
                fixed("delete"),
                nameArg);

        // /pattern show <name>
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = user(sender, tio);
                    if (u == null) return refuse();
                    String n = args.get("name");
                    var f =
                            repo.findByOwner(u).stream()
                                    .filter(p -> p.getName().equals(n))
                                    .findFirst();
                    if (f.isEmpty()) {
                        tio.error("Pattern not found: " + n);
                        return refuse();
                    }
                    var p = f.get();
                    tio.respond(
                            new TerminalResponse(
                                    ResponseType.TABLE,
                                    "",
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
                                                            p.getName().equals(active.get(u))
                                                                    ? "yes"
                                                                    : "no")))));
                    return allow();
                },
                fixed("show"),
                nameArg);

        // /pattern (no subcommand)
        addExecutor(
                (sender, args, io) -> {
                    ((TerminalIO) io).message("/pattern create|list|use|delete|show");
                    return allow();
                });
    }

    private List<String> completePatternName(CommandSender sender, Command cmd, String[] argv) {
        String u = sender instanceof VetoCommandSender vs ? vs.getUsername() : null;
        if (u == null) return List.of();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return repo.findByOwner(u).stream()
                .map(AgentPatternEntity::getName)
                .filter(n -> n.toLowerCase().startsWith(prefix))
                .toList();
    }

    private String user(CommandSender s, TerminalIO tio) {
        if (s instanceof VetoCommandSender vs && vs.isLoggedIn()) return vs.getUsername();
        tio.error("Not logged in");
        return null;
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/pattern <create|list|use|delete|show> [args...]");
    }
}
