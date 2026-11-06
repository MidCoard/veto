package top.focess.veto.secret;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultOptions;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.credentials.VaultAccess;

/** Reads only a scope-matched credential whose disclosure was admitted at import. */
@ToolSecurity(capability = ToolCapability.PRIVILEGED, defaultDanger = Danger.DANGEROUS)
@ToolDoc(
        description =
                """
        Read an authorized credential reference when the task needs its literal value.
        """,
        behavior =
                """
        Return the exact value when the current agent has permission to read the reference.\
        """,
        whenToUse =
                """
        When the task requires the literal value of an authorized credential reference.\
        """,
        whenNotToUse =
                """
        Authentication tools can use references directly without reading their values.\
        """,
        resultContract =
                """
        Successful output is the original value. Unauthorized or expired references fail with a diagnostic.\
        """,
        errorsAndEdgeCases =
                """
        The vault must be unlocked and the reference must still be available in this agent's scope.\
        """,
        security =
                """
        A reference alone does not grant permission to read it.\
        """,
        resultFormats = {ToolResultFormat.PLAINTEXT},
        examples = {
            "{\"reference\":\"cred_example\"}",
            "{\"reference\":\"cred_private\"}",
            "{\"reference\":\"cred_expired\"}"
        },
        returnExamples = {
            "AbC123!",
            "Tool execution failed: Credential is not model-readable",
            "Tool execution failed: Credential reference expired"
        })
public final class ReadCredentialTool extends NativeTool<ReadCredentialTool.Args> {
    private final @NonNull SecretAgent agent;
    private final @NonNull VaultAccess vault;

    ReadCredentialTool(@NonNull SecretAgent agent, @NonNull VaultAccess vault) {
        this.agent = agent;
        this.vault = vault;
    }

    @Override
    public @NonNull String getName() {
        return "read_credential";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        return agent.read(vault.open(Map.of("reference", args.reference())), args.reference());
    }

    @Override
    public @NonNull ToolResultOptions resultOptions(@NonNull Args args) {
        return new ToolResultOptions(Set.of(SecretAgent.PROCESSOR));
    }

    /** The credential identifier returned by an authorized import. */
    public record Args(
            @NonNull @Doc("The cred_ credential reference returned by import.") String reference) {}
}
