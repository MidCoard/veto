package top.focess.veto.command.commands;

import java.util.Arrays;
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
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierBindingEntity;
import top.focess.veto.model.tier.ModelTierField;
import top.focess.veto.model.tier.ModelTierProfileService;
import top.focess.veto.model.tier.ModelTierRegistry;

/**
 * Manages the per-user, runtime-switchable model-tier profiles. A user creates one or more named
 * profiles (e.g. {@code default}, {@code premium}), configures each of the three tiers (TOP / MID /
 * LOW, plus LOCAL) field by field, and switches which profile is active. Patterns and agents bind
 * to a tier name only; at activation the {@link ModelTierRegistry} resolves the tier against the
 * user's <em>active</em> profile, so {@code /modeltier use <profile>} swaps every pattern's and
 * agent's concrete model at the next resolution.
 *
 * <p>Subcommands:
 *
 * <ul>
 *   <li>{@code /modeltier create <name>} - create a profile (auto-activates if it is the user's
 *       first, so a brand-new user can resolve immediately without a separate {@code use}).
 *   <li>{@code /modeltier set <profile> <tier> <field> <value>} - set one field of one tier's
 *       binding. Fields: {@code provider} (OPENAI/ANTHROPIC/GEMINI/DEEPSEEK), {@code baseUrl},
 *       {@code model}, {@code credKey}, {@code temp}, {@code max}. A binding may be partial between
 *       calls; resolution fail-fasts if a required field (provider/model/credKey) is still unset.
 *   <li>{@code /modeltier use <profile>} - activate a profile (deactivates the user's others).
 *   <li>{@code /modeltier list} - list the user's profiles (the active one is marked).
 *   <li>{@code /modeltier show [profile]} - show a profile's tier bindings (defaults to the active
 *       profile).
 *   <li>{@code /modeltier delete <profile>} - delete a profile and its bindings.
 * </ul>
 */
public class ModelTierCommand extends VetoCommand {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.command.commands.ModelTierCommand");

    private final @NonNull ModelTierProfileService profileService;
    private final @NonNull ModelTierRegistry tierRegistry;

    public ModelTierCommand(
            @NonNull ModelTierProfileService profileService,
            @NonNull ModelTierRegistry tierRegistry) {
        super("modeltier", "Manage per-user model-tier profiles", "mt");
        this.profileService = profileService;
        this.tierRegistry = tierRegistry;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var nameArg = arg("name").description("New profile name");
        var profileArg =
                arg("profile").completer(this::completeProfile).description("Profile name");
        var tierArg =
                CommandArgument.of(VetoDataConverters.MODEL_TIER)
                        .named("tier")
                        .description("Model tier (TOP/MID/LOW/LOCAL)");
        var fieldArg =
                arg("field")
                        .completer(this::completeField)
                        .description("Field: provider|baseUrl|model|credKey|temp|max");
        var valueArg = arg("value").description("Field value");

        // /modeltier create <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String name = requiredArg(args.get("name"), "name");
                    try {
                        profileService.createProfile(s.requireUsername(), name);
                    } catch (IllegalArgumentException e) {
                        s.output("Cannot create profile: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("Profile '" + name + "' created.");
                    return CommandResult.ALLOW;
                },
                fixed("create").description("Create a new model-tier profile"),
                nameArg);

        // /modeltier set <profile> <tier> <field> <value>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String profile = requiredArg(args.get("profile"), "profile");
                    ModelTier tier = requiredArg(args.get("tier"), "tier");
                    String fieldStr = requiredArg(args.get("field"), "field");
                    String value = requiredArg(args.get("value"), "value");
                    ModelTierField field = ModelTierField.fromField(fieldStr);
                    if (field == null) {
                        s.output(
                                "Unknown field: "
                                        + fieldStr
                                        + ". Valid fields: provider, baseUrl, model, credKey,"
                                        + " temp, max.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        profileService.setField(s.requireUsername(), profile, tier, field, value);
                    } catch (IllegalArgumentException e) {
                        s.output("Cannot set " + fieldStr + ": " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("Set " + profile + " " + tier + " " + fieldStr + " = " + value + ".");
                    return CommandResult.ALLOW;
                },
                fixed("set").description("Set one field of a tier's binding"),
                profileArg,
                tierArg,
                fieldArg,
                valueArg);

        // /modeltier use <profile>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String profile = requiredArg(args.get("profile"), "profile");
                    try {
                        profileService.activateProfile(s.requireUsername(), profile);
                    } catch (IllegalArgumentException e) {
                        s.output("Cannot activate profile: " + e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("Active profile: " + profile);
                    return CommandResult.ALLOW;
                },
                fixed("use").description("Activate a profile"),
                profileArg);

        // /modeltier list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    var profiles = profileService.listProfiles(s.requireUsername());
                    if (profiles.isEmpty()) {
                        s.output("No model-tier profiles. Use /modeltier create <name> ...");
                        return CommandResult.ALLOW;
                    }
                    s.output("Model-tier profiles:");
                    for (var p : profiles) {
                        s.output((p.isActive() ? "  * " : "    ") + p.getName());
                    }
                    return CommandResult.ALLOW;
                },
                fixed("list").description("List your profiles"));

        // /modeltier show [profile]
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String target = args.get("profile");
                    if (target == null) {
                        target = tierRegistry.activeProfile(s.requireUsername());
                        if (target == null) {
                            s.output("No active profile. Use /modeltier use <profile>.");
                            return CommandResult.ALLOW;
                        }
                    }
                    var found = profileService.profile(s.requireUsername(), target);
                    if (found.isEmpty()) {
                        s.output("Profile not found: " + target);
                        return CommandResult.REFUSE;
                    }
                    s.output("Profile: " + target + (found.get().isActive() ? " (active)" : ""));
                    var bindings = profileService.bindings(s.requireUsername(), target);
                    if (bindings.isEmpty()) {
                        s.output(
                                "  No tier bindings configured. Use /modeltier set "
                                        + target
                                        + " <tier> <field> <value>.");
                    } else {
                        for (var b : bindings) {
                            s.output("  " + b.getTier() + ": " + describeBinding(b));
                        }
                    }
                    return CommandResult.ALLOW;
                },
                fixed("show").description("Show a profile's tier bindings"),
                opt("profile"));

        // /modeltier delete <profile>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String profile = requiredArg(args.get("profile"), "profile");
                    if (profileService.deleteProfile(s.requireUsername(), profile)) {
                        s.output("Profile '" + profile + "' deleted.");
                        return CommandResult.ALLOW;
                    }
                    s.output("Profile not found: " + profile);
                    return CommandResult.REFUSE;
                },
                fixed("delete").description("Delete a profile and its bindings"),
                profileArg);
    }

    /** Renders a binding's fields, marking unset ones. */
    private static @NonNull String describeBinding(@NonNull ModelTierBindingEntity b) {
        return "provider="
                + orUnset(b.getProvider())
                + " model="
                + orUnset(b.getModel())
                + " credKey="
                + orUnset(b.getCredentialKey())
                + " baseUrl="
                + orUnset(b.getBaseUrl())
                + " temp="
                + (b.getTemperature() != null ? b.getTemperature() : "(unset)")
                + " max="
                + (b.getMaxOutputTokens() != null ? b.getMaxOutputTokens() : "(unset)");
    }

    private static @NonNull String orUnset(Object o) {
        return o != null ? String.valueOf(o) : "(unset)";
    }

    private @NonNull List<CommandCompletion> completeProfile(
            @NonNull CommandSender sender, @NonNull Command cmd, @NonNull String @NonNull [] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String u = ((VetoCommandSender) sender).requireUsername();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return profileService.listProfiles(u).stream()
                .map(p -> p.getName())
                .filter(n -> n.toLowerCase().startsWith(prefix))
                .map(n -> CommandCompletion.of(n, "profile"))
                .toList();
    }

    private @NonNull List<CommandCompletion> completeField(
            @NonNull CommandSender sender, @NonNull Command cmd, @NonNull String @NonNull [] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        var fields = top.focess.veto.util.Nullness.requireNonNull(ModelTierField.values());
        return Arrays.stream(fields)
                .map(ModelTierField::field)
                .filter(f -> f.startsWith(prefix))
                .map(f -> CommandCompletion.of(f, "field"))
                .toList();
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        log.info("ModelTierCommand.usage() called");
        return List.of(
                "/modeltier create <name> - Create a profile (auto-activates if first)",
                "/modeltier set <profile> <tier> <field> <value> - Set a tier binding field"
                        + " (field: provider|baseUrl|model|credKey|temp|max)",
                "/modeltier use <profile> - Activate a profile",
                "/modeltier list - List your profiles (active marked with *)",
                "/modeltier show [profile] - Show a profile's bindings (default: active)",
                "/modeltier delete <profile> - Delete a profile");
    }
}
