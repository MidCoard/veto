package top.focess.veto.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.ExecutionResult;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierBindingEntity;
import top.focess.veto.model.tier.ModelTierField;
import top.focess.veto.model.tier.ModelTierProfileEntity;
import top.focess.veto.model.tier.ModelTierProfileService;
import top.focess.veto.model.tier.ModelTierRegistry;

/**
 * Verifies the {@code /modeltier} command dispatches to {@link ModelTierProfileService} / {@link
 * ModelTierRegistry} and renders the right output for each subcommand. The service layer is covered
 * by {@link top.focess.veto.model.tier.DefaultModelTierServiceTest}; this test fixes the command
 * wiring (argument parsing, per-field validation routing, output messages).
 */
class ModelTierCommandTest {

    private static @NonNull VetoCommandSender aliceSender() {
        VetoCommandSender sender = mock(ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        CommandPermission.class))))
                .thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.requireUsername()).thenReturn("alice");
        return sender;
    }

    private static @NonNull CommandManager manager(
            @NonNull ModelTierProfileService profiles, @NonNull ModelTierRegistry tiers) {
        CommandManager manager = new CommandManager();
        manager.register(new ModelTierCommand(profiles, tiers));
        return manager;
    }

    @Test
    void createDispatchesToService() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        ExecutionResult result =
                manager(profiles, tiers).dispatch(aliceSender(), "modeltier create default");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(profiles).createProfile("alice", "default");
    }

    @Test
    void createRefusesDuplicate() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        doThrow(new IllegalArgumentException("Profile 'default' already exists"))
                .when(profiles)
                .createProfile("alice", "default");
        VetoCommandSender sender = aliceSender();

        ExecutionResult result =
                manager(profiles, tiers).dispatch(sender, "modeltier create default");

        assertEquals(CommandResult.REFUSE, result.result());
        verify(sender).output("Cannot create profile: Profile 'default' already exists");
    }

    @Test
    void setDispatchesPerField() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        ExecutionResult result =
                manager(profiles, tiers)
                        .dispatch(aliceSender(), "modeltier set default TOP provider deepseek");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(profiles)
                .setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
    }

    @Test
    void setRefusesUnknownField() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        VetoCommandSender sender = aliceSender();

        ExecutionResult result =
                manager(profiles, tiers).dispatch(sender, "modeltier set default TOP bogus value");

        assertEquals(CommandResult.REFUSE, result.result());
        verify(sender).output(startsWith("Unknown field: bogus"));
        verify(profiles, never()).setField(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void setRefusesInvalidValue() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        doThrow(new IllegalArgumentException("Unknown provider: nope"))
                .when(profiles)
                .setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "nope");
        VetoCommandSender sender = aliceSender();

        ExecutionResult result =
                manager(profiles, tiers)
                        .dispatch(sender, "modeltier set default TOP provider nope");

        assertEquals(CommandResult.REFUSE, result.result());
        verify(sender).output("Cannot set provider: Unknown provider: nope");
    }

    @Test
    void useActivatesProfile() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        VetoCommandSender sender = aliceSender();

        ExecutionResult result = manager(profiles, tiers).dispatch(sender, "modeltier use premium");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(profiles).activateProfile("alice", "premium");
        verify(sender).output("Active profile: premium");
    }

    @Test
    void listReportsNoProfiles() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(profiles.listProfiles("alice")).thenReturn(List.of());
        VetoCommandSender sender = aliceSender();

        ExecutionResult result = manager(profiles, tiers).dispatch(sender, "modeltier list");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).output("No model-tier profiles. Use /modeltier create <name> ...");
    }

    @Test
    void listMarksActiveProfile() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(profiles.listProfiles("alice"))
                .thenReturn(
                        List.of(
                                new ModelTierProfileEntity("default", "alice", true),
                                new ModelTierProfileEntity("premium", "alice", false)));
        VetoCommandSender sender = aliceSender();

        ExecutionResult result = manager(profiles, tiers).dispatch(sender, "modeltier list");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).output("Model-tier profiles:");
        verify(sender).output("  * default");
        verify(sender).output("    premium");
    }

    @Test
    void showDefaultsToActiveProfile() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(tiers.activeProfile("alice")).thenReturn("default");
        when(profiles.profile("alice", "default"))
                .thenReturn(Optional.of(new ModelTierProfileEntity("default", "alice", true)));
        ModelTierBindingEntity binding = new ModelTierBindingEntity("pid", ModelTier.TOP);
        binding.setProvider(ProviderType.DEEPSEEK);
        binding.setModel("deepseek-chat");
        binding.setCredentialKey("dk");
        when(profiles.bindings("alice", "default")).thenReturn(List.of(binding));
        VetoCommandSender sender = aliceSender();

        ExecutionResult result = manager(profiles, tiers).dispatch(sender, "modeltier show");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).output("Profile: default (active)");
        verify(sender)
                .output(
                        "  TOP: provider=DEEPSEEK model=deepseek-chat credKey=dk baseUrl=(unset)"
                                + " temp=(unset) max=(unset)");
    }

    @Test
    void showWithoutActiveProfileAdvisesUse() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(tiers.activeProfile("alice")).thenReturn(null);
        VetoCommandSender sender = aliceSender();

        ExecutionResult result = manager(profiles, tiers).dispatch(sender, "modeltier show");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).output("No active profile. Use /modeltier use <profile>.");
    }

    @Test
    void deleteDispatchesAndConfirms() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(profiles.deleteProfile("alice", "old")).thenReturn(true);
        VetoCommandSender sender = aliceSender();

        ExecutionResult result = manager(profiles, tiers).dispatch(sender, "modeltier delete old");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).output("Profile 'old' deleted.");
    }

    @Test
    void deleteRefusesMissingProfile() {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        ModelTierRegistry tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(profiles.deleteProfile("alice", "missing")).thenReturn(false);
        VetoCommandSender sender = aliceSender();

        ExecutionResult result =
                manager(profiles, tiers).dispatch(sender, "modeltier delete missing");

        assertEquals(CommandResult.REFUSE, result.result());
        verify(sender).output("Profile not found: missing");
    }
}
