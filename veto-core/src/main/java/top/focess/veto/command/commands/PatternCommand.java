package top.focess.veto.command.commands;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import top.focess.command.Command;
import top.focess.command.CommandArgument;
import top.focess.command.CommandResult;
import top.focess.command.DataConverter;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.vault.CredentialVault;

public class PatternCommand extends Command {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CredentialVault vault;
    private final ConcurrentHashMap<String, String> active;
    private final Path dir;

    private record AgentPattern(
            String name,
            String provider,
            String model,
            String credentialKey,
            String systemPrompt) {
    }

    public PatternCommand(CredentialVault v, ConcurrentHashMap<String, String> a, Path vh) {
        super("pattern", "ap");
        this.vault = v;
        this.active = a;
        this.dir = vh.resolve("terminal/patterns");

        // /pattern create <name> <provider> <model> <apikey> [sysprompt]
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = currentUser(tio);
                    if (u == null) return CommandResult.REFUSE;
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
                        List<AgentPattern> pats = load(u);
                        pats.removeIf(x -> x.name().equals(n));
                        pats.add(new AgentPattern(n, p, m, "pattern-" + n, sp));
                        save(u, pats);
                        tio.message("Pattern '" + n + "' created (" + p + "/" + m + ").");
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException e) {
                        tio.error("Unknown provider");
                        return CommandResult.REFUSE;
                    } catch (Exception e) {
                        tio.error(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                CommandArgument.of("create"),
                CommandArgument.ofString().named("name"),
                CommandArgument.ofString().named("provider"),
                CommandArgument.ofString().named("model"),
                CommandArgument.ofString().named("apikey"),
                CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER)
                        .named("sysprompt"));

        // /pattern list
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = currentUser(tio);
                    if (u == null) return CommandResult.REFUSE;
                    try {
                        String act = active.get(u);
                        List<AgentPattern> pats = load(u);
                        if (pats.isEmpty()) {
                            tio.message(
                                    "No patterns. /pattern create <name> <provider> <model> <apikey>");
                            return CommandResult.ALLOW;
                        }
                        tio.respond(
                                new TerminalResponse(
                                        ResponseType.TABLE,
                                        "",
                                        Map.of(
                                                "headers",
                                                List.of(
                                                        "NAME",
                                                        "PROVIDER",
                                                        "MODEL",
                                                        "ACTIVE"),
                                                "rows",
                                                pats.stream()
                                                        .map(
                                                                x ->
                                                                        List.of(
                                                                                x.name()
                                                                                        + (x.name()
                                                                                        .equals(
                                                                                                act)
                                                                                        ? " *"
                                                                                        : ""),
                                                                                x
                                                                                        .provider(),
                                                                                x.model(),
                                                                                x.name()
                                                                                        .equals(
                                                                                                act)
                                                                                        ? "✓"
                                                                                        : ""))
                                                        .toList())));
                        return CommandResult.ALLOW;
                    } catch (Exception e) {
                        tio.error(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                CommandArgument.of("list"));

        // /pattern use <name>
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = currentUser(tio);
                    if (u == null) return CommandResult.REFUSE;
                    try {
                        String n = args.get("name");
                        var f = load(u).stream().filter(x -> x.name().equals(n)).findFirst();
                        if (f.isEmpty()) {
                            tio.error("Pattern not found: " + n);
                            return CommandResult.REFUSE;
                        }
                        active.put(u, n);
                        tio.message(
                                "Using '"
                                        + n
                                        + "' ("
                                        + f.get().provider()
                                        + "/"
                                        + f.get().model()
                                        + ").");
                        return CommandResult.ALLOW;
                    } catch (Exception e) {
                        tio.error(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                CommandArgument.of("use"),
                CommandArgument.ofString().named("name"));

        // /pattern delete <name>
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = currentUser(tio);
                    if (u == null) return CommandResult.REFUSE;
                    try {
                        String n = args.get("name");
                        List<AgentPattern> pats = load(u);
                        if (!pats.removeIf(x -> x.name().equals(n))) {
                            tio.error("Pattern not found: " + n);
                            return CommandResult.REFUSE;
                        }
                        save(u, pats);
                        if (n.equals(active.get(u))) active.remove(u);
                        try {
                            vault.delete("pattern-" + n);
                        } catch (Exception ign) {
                        }
                        tio.message("Pattern '" + n + "' deleted.");
                        return CommandResult.ALLOW;
                    } catch (Exception e) {
                        tio.error(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                CommandArgument.of("delete"),
                CommandArgument.ofString().named("name"));

        // /pattern show <name>
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    String u = currentUser(tio);
                    if (u == null) return CommandResult.REFUSE;
                    try {
                        String n = args.get("name");
                        var f = load(u).stream().filter(x -> x.name().equals(n)).findFirst();
                        if (f.isEmpty()) {
                            tio.error("Pattern not found: " + n);
                            return CommandResult.REFUSE;
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
                                                        List.of("Name", p.name()),
                                                        List.of("Provider", p.provider()),
                                                        List.of("Model", p.model()),
                                                        List.of("System Prompt", p.systemPrompt()),
                                                        List.of(
                                                                "Active",
                                                                p.name().equals(active.get(u))
                                                                        ? "yes"
                                                                        : "no")))));
                        return CommandResult.ALLOW;
                    } catch (Exception e) {
                        tio.error(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                CommandArgument.of("show"),
                CommandArgument.ofString().named("name"));

        // /pattern (no subcommand) → help
        addExecutor(
                (sender, args, io) -> {
                    ((TerminalIO) io)
                            .message(
                                    "/pattern create <name> <provider> <model> <apikey> [sysprompt]\n"
                                            + "/pattern list\n/pattern use <name>\n/pattern delete <name>\n/pattern show <name>");
                    return CommandResult.ALLOW;
                });
    }

    private String currentUser(TerminalIO tio) {
        String u = vault.getCurrentUser();
        if (u == null) tio.error("Not logged in");
        return u;
    }

    private List<AgentPattern> load(String u) throws IOException {
        Path f = dir.resolve(u + ".json");
        return Files.exists(f)
                ? JSON.readValue(f.toFile(), new TypeReference<>() {
        })
                : new ArrayList<>();
    }

    private void save(String u, List<AgentPattern> p) throws IOException {
        Path f = dir.resolve(u + ".json");
        Files.createDirectories(f.getParent());
        JSON.writeValue(f.toFile(), p);
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(top.focess.command.CommandSender s) {
        return List.of("/pattern <create|list|use|delete|show> [args...]");
    }
}
