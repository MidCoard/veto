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
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.KeysteadVault;

/**
 * Verifies that {@code /pattern create} no longer accepts or stores a user-controllable system
 * prompt. The system prompt is persona-derived in {@code PromptCompiler} (Layer 1 base + VETO.md +
 * skills); the pattern entity must not carry it.
 */
class PatternCommandSystemPromptTest {

    @Test
    void createDoesNotStoreSystemPrompt() {
        AgentPatternRepository repo = mock(AgentPatternRepository.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
        when(sender.isLoggedIn()).thenReturn(true);
        when(sender.username()).thenReturn("alice");
        when(sender.input(anyString(), anyBoolean())).thenReturn("sk-key");

        // The PatternCommand constructor calls init() itself; registering attaches the manager
        // used for in-process dispatch.
        CommandManager manager = new CommandManager();
        manager.register(new PatternCommand(vault, repo));

        ArgumentCaptor<AgentPatternEntity> captor =
                ArgumentCaptor.forClass(AgentPatternEntity.class);

        ExecutionResult result = manager.dispatch(sender, "pattern create p1 DEEPSEEK deepseek-v4");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(repo).save(captor.capture());
        AgentPatternEntity saved = captor.getValue();
        assertEquals("p1", saved.getName());
        assertEquals("DEEPSEEK", saved.getProvider());
        assertEquals("deepseek-v4", saved.getModel());
        assertEquals("pattern-p1", saved.getCredentialKey());
        assertEquals("alice", saved.getOwner());

        // The system prompt is persona-derived in PromptCompiler; the pattern must not store it.
        // With the field removed from the entity, this is the structural guarantee it is gone.
        assertThrows(
                NoSuchFieldException.class,
                () -> AgentPatternEntity.class.getDeclaredField("systemPrompt"),
                "AgentPatternEntity must not carry a systemPrompt field");
    }
}
