package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.CredentialImportCapability;

public non-sealed interface CredentialImportTool<T> extends NativeTool<T> {
    @NonNull CredentialImportCapability credentialImportCapability();

    @NonNull String execute(@NonNull T args, @NonNull CredentialImportCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, credentialImportCapability());
    }
}
