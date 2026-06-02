package top.focess.veto.terminal;

import top.focess.veto.agent.Agent;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.vault.CredentialVault;
import top.focess.veto.vault.UserRegistry;
import top.focess.veto.vault.VaultKeyManager;

/**
 * Shared mutable state passed to all terminal commands.
 */
public class TerminalContext {

    public final UserRegistry users;
    public final VaultKeyManager keys;
    public final CredentialVault vault;
    public final UniformLLMCaller caller;
    public final VetoCommandSender sender;

    public Agent currentAgent;

    public TerminalContext(
            UserRegistry users,
            VaultKeyManager keys,
            CredentialVault vault,
            UniformLLMCaller caller) {
        this.users = users;
        this.keys = keys;
        this.vault = vault;
        this.caller = caller;
        this.sender = new VetoCommandSender();
    }
}
