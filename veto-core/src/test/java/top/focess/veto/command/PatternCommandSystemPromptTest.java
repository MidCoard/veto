package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.ExecutionResult;
import top.focess.veto.command.commands.PatternCommand;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;

/**
 * Verifies that {@code /pattern create <name> <tier>} binds the pattern to a model tier (resolved
 * live via the model-tier registry for the owner's active profile) and that the pattern still
 * carries no user-controllable system prompt. The system prompt is persona-derived in {@code
 * PromptCompiler}.
 */
class PatternCommandSystemPromptTest {

    @Test
    void createBindsToTierAndDoesNotStoreSystemPrompt() {
        AgentPatternRepository repo =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        VetoCommandSender sender =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        CommandPermission.class))))
                .thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.requireUsername()).thenReturn("alice");

        ModelTierRegistry tierRegistry =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(tierRegistry.resolve("alice", ModelTier.TOP))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK,
                                "deepseek-chat",
                                "deepseek-default",
                                0.7,
                                4096,
                                null));

        CommandManager manager = new CommandManager();
        manager.register(new PatternCommand(repo, tierRegistry));

        ArgumentCaptor<AgentPatternEntity> captor =
                ArgumentCaptor.forClass(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternEntity.class));

        ExecutionResult result = manager.dispatch(sender, "pattern create p1 TOP");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(repo).save(captor.capture());
        AgentPatternEntity saved = captor.getValue();
        assertEquals("p1", saved.getName());
        assertEquals(ModelTier.TOP, saved.getTier());
        // Cache populated from the resolved TOP binding for alice's active profile.
        assertEquals("DEEPSEEK", saved.getProvider());
        assertEquals("deepseek-chat", saved.getModel());
        assertEquals("deepseek-default", saved.getCredentialKey());
        assertEquals("alice", saved.getOwner());

        // The system prompt is persona-derived in PromptCompiler; the pattern must not store it.
        // With the field removed from the entity, this is the structural guarantee it is gone.
        assertThrows(
                NoSuchFieldException.class,
                () ->
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternEntity.class)
                                .getDeclaredField("systemPrompt"),
                "AgentPatternEntity must not carry a systemPrompt field");
    }
}
