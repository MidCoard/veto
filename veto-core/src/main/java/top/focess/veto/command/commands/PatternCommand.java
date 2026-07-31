package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.Command;
import top.focess.command.CommandArgument;
import top.focess.command.CommandCompletion;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.command.VetoDataConverters;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;

public class PatternCommand extends VetoCommand {

    private static final Logger log = LoggerFactory.getLogger(PatternCommand.class);

    private final AgentPatternRepository repo;
    private final ModelTierRegistry tierRegistry;

    public PatternCommand(
            @NonNull AgentPatternRepository repo, @NonNull ModelTierRegistry tierRegistry) {
        super("pattern", "Manage agent patterns", "ap");
        this.repo = repo;
        this.tierRegistry = tierRegistry;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var nameArg = arg("name").completer(this::completePatternName).description("Pattern name");
        // The tier arg is parsed by the framework's DataConverter.ofEnum - case-insensitive
        // accept/convert gives free validation (unknown tiers never reach the executor) and the
        // enum constants drive tab-completion, so no custom completer is needed. The converter is
        // a shared, pre-registered instance (VetoDataConverters.MODEL_TIER); an ad-hoc ofEnum would
        // have no buffer and NPE the DataCollection constructor during dispatch.
        var tierArg =
                CommandArgument.of(VetoDataConverters.MODEL_TIER)
                        .named("tier")
                        .description("Model tier");

        // /pattern create <name> <tier>
        // The pattern binds to a model tier; the concrete provider/model/credential come from the
        // active model-tier configuration (veto.model-tiers), resolved live at activation. The
        // system prompt is persona-derived in PromptCompiler; commands must not set or store it.
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String n = args.get("name");
                    ModelTier tier = args.get("tier");
                    ModelBinding cache = tierRegistry.resolve(tier);
                    AgentPatternEntity entity =
                            new AgentPatternEntity(n, tier, cache, s.username());
                    repo.save(entity);
                    s.output(
                            "Pattern '"
                                    + n
                                    + "' created (tier="
                                    + tier
                                    + " -> "
                                    + cache.provider()
                                    + "/"
                                    + cache.model()
                                    + ").");
                    return CommandResult.ALLOW;
                },
                fixed("create").description("Create a new agent pattern bound to a model tier"),
                arg("name"),
                tierArg);

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
                        ModelBinding live = tierRegistry.resolve(p.getTier());
                        s.output(
                                String.format(
                                        "  %-16s tier=%-5s -> %s/%s",
                                        p.getName(), p.getTier(), live.provider(), live.model()));
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
                    ModelBinding live = tierRegistry.resolve(p.getTier());
                    s.output("Pattern: " + p.getName());
                    s.output("  Tier:          " + p.getTier());
                    s.output("  Active config: " + tierRegistry.activeProfile());
                    s.output("  Resolved:      " + live.provider() + "/" + live.model());
                    s.output(
                            "  Credential:    "
                                    + live.credentialKey()
                                    + " (set via /credential set <key>)");
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
                .map(p -> CommandCompletion.of(p.getName(), "tier=" + p.getTier()))
                .toList();
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        log.info("PatternCommand.usage() called");
        return List.of(
                "/pattern create <name> <tier> - Create a pattern bound to a model tier"
                        + " (TOP/MID/LOW/LOCAL)",
                "/pattern list - List your patterns",
                "/pattern delete <name> - Delete a pattern",
                "/pattern show <name> - Show pattern details");
    }
}
