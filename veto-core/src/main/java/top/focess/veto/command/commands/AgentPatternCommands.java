package top.focess.veto.command.commands;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.vault.CredentialVault;

/**
 * Handles /ap (agent-pattern) subcommands. Registered as multiple CommandHandlers: pattern-create,
 * pattern-list, pattern-use, pattern-delete, pattern-show.
 */
public class AgentPatternCommands {

    private static final Logger log = LoggerFactory.getLogger(AgentPatternCommands.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final CredentialVault vault;
    private final ConcurrentHashMap<String, String> activePatterns;
    private final Path patternsDir;

    private record AgentPattern(
            String name,
            String provider,
            String model,
            String credentialKey,
            String systemPrompt) {
    }

    public AgentPatternCommands(
            CredentialVault vault,
            ConcurrentHashMap<String, String> activePatterns,
            Path vaultHome) {
        this.vault = vault;
        this.activePatterns = activePatterns;
        this.patternsDir = vaultHome.resolve("terminal/patterns");
    }

    public List<CommandHandler> all() {
        return List.of(
                createHandler(), listHandler(), useHandler(), deleteHandler(), showHandler());
    }

    private CommandHandler createHandler() {
        return new CommandHandler() {
            @Override
            public String name() {
                return "pattern-create";
            }

            @Override
            public String description() {
                return "Create a new agent pattern";
            }

            @Override
            public String usage() {
                return "pattern-create <name> <provider> <model> <apikey> [systemPrompt]";
            }

            @Override
            public List<ArgDef> arguments() {
                return List.of(
                        new ArgDef("name", "string", true, "Pattern name"),
                        new ArgDef(
                                "provider",
                                "string",
                                true,
                                "Provider: DEEPSEEK, OPENAI, ANTHROPIC, GEMINI"),
                        new ArgDef("model", "string", true, "Model name"),
                        new ArgDef("apikey", "string", true, "API key"),
                        new ArgDef("systemPrompt", "string", false, "System prompt"));
            }

            @Override
            public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
                String user = vault.getCurrentUser();
                if (user == null) return TerminalResponse.error("Not logged in");

                String name = (String) args.get("arg1");
                String provider = (String) args.get("arg2");
                String model = (String) args.get("arg3");
                String apiKey = (String) args.get("arg4");
                String sysPrompt =
                        (String)
                                args.getOrDefault(
                                        "arg5", "You are a helpful coding assistant. Be concise.");

                if (name == null || provider == null || model == null || apiKey == null)
                    return TerminalResponse.error(
                            "Usage: pattern-create <name> <provider> <model> <apikey>"
                                    + " [systemPrompt]");

                try {
                    ProviderType.valueOf(provider.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return TerminalResponse.error("Unknown provider: " + provider);
                }

                try {
                    String credKey = "pattern-" + name;
                    vault.store(credKey, apiKey);
                    List<AgentPattern> patterns = loadPatterns(user);
                    patterns.removeIf(p -> p.name().equals(name));
                    patterns.add(
                            new AgentPattern(
                                    name, provider.toUpperCase(), model, credKey, sysPrompt));
                    savePatterns(user, patterns);
                    return new TerminalResponse(
                            ResponseType.MESSAGE,
                            "Pattern '" + name + "' created (" + provider + "/" + model + ").");
                } catch (Exception e) {
                    return TerminalResponse.error("Failed to create pattern: " + e.getMessage());
                }
            }
        };
    }

    private CommandHandler listHandler() {
        return new CommandHandler() {
            @Override
            public String name() {
                return "pattern-list";
            }

            @Override
            public String description() {
                return "List all agent patterns";
            }

            @Override
            public String usage() {
                return "pattern-list";
            }

            @Override
            public List<ArgDef> arguments() {
                return List.of();
            }

            @Override
            public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
                String user = vault.getCurrentUser();
                if (user == null) return TerminalResponse.error("Not logged in");

                try {
                    String active = activePatterns.get(user);
                    List<AgentPattern> patterns = loadPatterns(user);
                    if (patterns.isEmpty())
                        return new TerminalResponse(
                                ResponseType.MESSAGE,
                                "No patterns defined. Create one: pattern-create <name>"
                                        + " <provider> <model> <apikey>");

                    List<String> headers = List.of("NAME", "PROVIDER", "MODEL", "ACTIVE");
                    List<List<String>> rows = new ArrayList<>();
                    for (AgentPattern p : patterns) {
                        String displayName = p.name() + (p.name().equals(active) ? " *" : "");
                        rows.add(
                                List.of(
                                        displayName,
                                        p.provider(),
                                        p.model(),
                                        p.name().equals(active) ? "✓" : ""));
                    }
                    return new TerminalResponse(
                            ResponseType.TABLE, "", Map.of("headers", headers, "rows", rows));
                } catch (Exception e) {
                    return TerminalResponse.error("Failed to list patterns: " + e.getMessage());
                }
            }
        };
    }

    private CommandHandler useHandler() {
        return new CommandHandler() {
            @Override
            public String name() {
                return "pattern-use";
            }

            @Override
            public String description() {
                return "Set active agent pattern";
            }

            @Override
            public String usage() {
                return "pattern-use <name>";
            }

            @Override
            public List<ArgDef> arguments() {
                return List.of(new ArgDef("name", "string", true, "Pattern name"));
            }

            @Override
            public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
                String user = vault.getCurrentUser();
                if (user == null) return TerminalResponse.error("Not logged in");

                String name = (String) args.get("arg1");
                if (name == null) return TerminalResponse.error("Usage: pattern-use <name>");

                try {
                    return loadPatterns(user).stream()
                            .filter(p -> p.name().equals(name))
                            .findFirst()
                            .map(
                                    p -> {
                                        activePatterns.put(user, name);
                                        return new TerminalResponse(
                                                ResponseType.MESSAGE,
                                                "Using pattern '"
                                                        + name
                                                        + "' ("
                                                        + p.provider()
                                                        + "/"
                                                        + p.model()
                                                        + ").");
                                    })
                            .orElse(TerminalResponse.error("Pattern not found: " + name));
                } catch (Exception e) {
                    return TerminalResponse.error("Failed: " + e.getMessage());
                }
            }
        };
    }

    private CommandHandler deleteHandler() {
        return new CommandHandler() {
            @Override
            public String name() {
                return "pattern-delete";
            }

            @Override
            public String description() {
                return "Delete an agent pattern";
            }

            @Override
            public String usage() {
                return "pattern-delete <name>";
            }

            @Override
            public List<ArgDef> arguments() {
                return List.of(new ArgDef("name", "string", true, "Pattern name"));
            }

            @Override
            public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
                String user = vault.getCurrentUser();
                if (user == null) return TerminalResponse.error("Not logged in");

                String name = (String) args.get("arg1");
                if (name == null) return TerminalResponse.error("Usage: pattern-delete <name>");

                try {
                    List<AgentPattern> patterns = loadPatterns(user);
                    boolean removed = patterns.removeIf(p -> p.name().equals(name));
                    if (!removed) return TerminalResponse.error("Pattern not found: " + name);
                    savePatterns(user, patterns);
                    if (name.equals(activePatterns.get(user))) activePatterns.remove(user);
                    try {
                        vault.delete("pattern-" + name);
                    } catch (Exception ignored) {
                    }
                    return new TerminalResponse(
                            ResponseType.MESSAGE, "Pattern '" + name + "' deleted.");
                } catch (Exception e) {
                    return TerminalResponse.error("Failed: " + e.getMessage());
                }
            }
        };
    }

    private CommandHandler showHandler() {
        return new CommandHandler() {
            @Override
            public String name() {
                return "pattern-show";
            }

            @Override
            public String description() {
                return "Show pattern details";
            }

            @Override
            public String usage() {
                return "pattern-show <name>";
            }

            @Override
            public List<ArgDef> arguments() {
                return List.of(new ArgDef("name", "string", true, "Pattern name"));
            }

            @Override
            public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
                String user = vault.getCurrentUser();
                if (user == null) return TerminalResponse.error("Not logged in");

                String name = (String) args.get("arg1");
                if (name == null) return TerminalResponse.error("Usage: pattern-show <name>");

                try {
                    return loadPatterns(user).stream()
                            .filter(p -> p.name().equals(name))
                            .findFirst()
                            .map(
                                    p ->
                                            new TerminalResponse(
                                                    ResponseType.TABLE,
                                                    "",
                                                    Map.of(
                                                            "headers",
                                                            List.of("Property", "Value"),
                                                            "rows",
                                                            List.of(
                                                                    List.of("Name", p.name()),
                                                                    List.of(
                                                                            "Provider",
                                                                            p.provider()),
                                                                    List.of("Model", p.model()),
                                                                    List.of(
                                                                            "System Prompt",
                                                                            p.systemPrompt()),
                                                                    List.of(
                                                                            "Active",
                                                                            p.name()
                                                                                    .equals(
                                                                                            activePatterns
                                                                                                    .get(
                                                                                                            user))
                                                                                    ? "yes"
                                                                                    : "no")))))
                            .orElse(TerminalResponse.error("Pattern not found: " + name));
                } catch (Exception e) {
                    return TerminalResponse.error("Failed: " + e.getMessage());
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<AgentPattern> loadPatterns(String username) throws IOException {
        Path file = patternsDir.resolve(username + ".json");
        if (!Files.exists(file)) return new ArrayList<>();
        return json.readValue(file.toFile(), new TypeReference<List<AgentPattern>>() {
        });
    }

    private void savePatterns(String username, List<AgentPattern> patterns) throws IOException {
        Path file = patternsDir.resolve(username + ".json");
        Files.createDirectories(file.getParent());
        json.writeValue(file.toFile(), patterns);
    }
}
