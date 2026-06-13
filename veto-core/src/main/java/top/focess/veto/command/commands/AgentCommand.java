package top.focess.veto.command.commands;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import top.focess.command.Command;
import top.focess.command.CommandCompletion;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentPatternRepository;

/** Command that manages the active agent session configuration. */
public class AgentCommand extends VetoCommand {

    private final AgentPatternRepository repo;
    private final PromptHandler promptHandler;

    public AgentCommand(
            @NotNull AgentPatternRepository repo, @NotNull PromptHandler promptHandler) {
        super("agent", "Manage active agent");
        this.repo = repo;
        this.promptHandler = promptHandler;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var nameArg = arg("name").completer(this::completePatternName).description("Pattern name");

        // /agent use <name>
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
                    promptHandler.usePattern(s.username(), n);
                    s.output(
                            "Using agent pattern '"
                                    + n
                                    + "' ("
                                    + found.get().getProvider()
                                    + "/"
                                    + found.get().getModel()
                                    + ").");
                    return CommandResult.ALLOW;
                },
                fixed("use").description("Select an agent pattern to use"),
                nameArg);

        // /agent create <provider> <model> [sysprompt]
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    try {
                        String providerStr = args.get("provider");
                        ProviderType provider = ProviderType.valueOf(providerStr.toUpperCase());
                        String model = args.get("model");
                        String sp =
                                args.getOrDefault(
                                        "sysprompt",
                                        "You are a helpful coding assistant. Be concise.");

                        String key = s.input("API Key for " + provider + ":", true);
                        if (key == null || key.isEmpty()) {
                            s.output("Agent creation cancelled.");
                            return CommandResult.REFUSE;
                        }

                        promptHandler.setAdhocConfig(s.username(), provider, model, sp, key);
                        s.output("Ad-hoc agent created (" + provider + "/" + model + ").");
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException e) {
                        s.output("Unknown provider: " + args.get("provider"));
                        return CommandResult.REFUSE;
                    } catch (Exception e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                fixed("create").description("Create an ad-hoc agent directly without a pattern"),
                arg("provider").description("LLM provider (e.g. DEEPSEEK, OPENAI)"),
                arg("model").description("Model name"),
                opt("sysprompt").description("Custom system prompt"));

        // /agent stop
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    promptHandler.deactivateAgent(s.username());
                    s.output("Agent deactivated.");
                    return CommandResult.ALLOW;
                },
                fixed("stop").description("Deactivate the active agent"));

        // /agent status
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    PromptHandler.LlmConfig config = promptHandler.resolveLlmConfig(s.username());
                    if (config == null) {
                        s.output(
                                "No agent is currently active. Use /agent use <patternName> or"
                                        + " /agent create <provider> <model> to activate one.");
                        return CommandResult.ALLOW;
                    }
                    s.output("Active Agent Info:");
                    boolean isAdhoc = config.credKey().startsWith("agent-adhoc-");
                    s.output(
                            "  Type:          " + (isAdhoc ? "Ad-hoc (One-off)" : "Pattern-based"));
                    if (!isAdhoc) {
                        String patName = promptHandler.getActivePatternName(s.username());
                        s.output("  Pattern Name:  " + patName);
                    }
                    s.output("  Provider:      " + config.provider());
                    s.output("  Model:         " + config.model());
                    s.output("  System Prompt: " + config.systemPrompt());
                    return CommandResult.ALLOW;
                },
                fixed("status").description("Show current active agent details"));
    }

    @NotNull
    private List<CommandCompletion> completePatternName(
            @NotNull CommandSender sender, @NotNull Command cmd, @NotNull String[] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String u = ((VetoCommandSender) sender).username();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return repo.findByOwner(u).stream()
                .filter(p -> p.getName().toLowerCase().startsWith(prefix))
                .map(p -> CommandCompletion.of(p.getName(), p.getProvider() + " / " + p.getModel()))
                .toList();
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of(
                "/agent use <name> — Select an agent pattern to use",
                "/agent create <provider> <model> [sysprompt] — Create an ad-hoc agent directly without a pattern",
                "/agent stop — Deactivate the active agent",
                "/agent status — Show current active agent details");
    }
}
