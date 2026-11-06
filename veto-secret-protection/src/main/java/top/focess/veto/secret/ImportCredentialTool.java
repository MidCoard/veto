package top.focess.veto.secret;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.PreparedTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Imports exact source values selected by the plugin's local-model Agent. */
@ToolSecurity(
        capability = ToolCapability.PRIVILEGED,
        defaultDanger = Danger.SAFE,
        explicitSelectionOnly = true)
@ToolDoc(
        description =
                """
        Import one exact credential from the current source; public disclosure requires approval.
        """,
        behavior =
                """
        Store an exact source value and return its credential reference.\
        """,
        whenToUse =
                """
        For each distinct credential in the current source, including public values.\
        """,
        whenNotToUse =
                """
        Do not import placeholders, credential references, or invented values.\
        """,
        resultContract =
                """
        Successful output is a cred_ identifier. Failure contains a diagnostic and stores no authorized reference.\
        """,
        errorsAndEdgeCases =
                """
        Preserve the exact case and complete private-key block. Metadata must not contain the secret.\
        """,
        security =
                """
        Only the active Secret Agent may import. PRIVATE is the default; PUBLIC requests disclosure approval.\
        """,
        resultFormats = {ToolResultFormat.PLAINTEXT},
        examples = {
            "{\"value\":\"AbC123!\",\"visibility\":\"PRIVATE\",\"service\":\"database\",\"label\":\"Login\"}",
            "{\"value\":\"AbC123!\",\"visibility\":\"PUBLIC\",\"service\":\"database\",\"label\":\"Login\"}",
            "{\"value\":\"absent-value\",\"visibility\":\"PRIVATE\",\"service\":\"database\",\"label\":\"Login\"}"
        },
        returnExamples = {
            "cred_example_private",
            "cred_example_public",
            "Tool execution failed: Credential must be an exact nonempty source value"
        })
public final class ImportCredentialTool extends PreparedTool<ImportCredentialTool.Args> {
    private final @NonNull SecretAgent agent;
    private final @NonNull VaultAccess vault;

    ImportCredentialTool(@NonNull SecretAgent agent, @NonNull VaultAccess vault) {
        this.agent = agent;
        this.vault = vault;
    }

    @Override
    public @NonNull String getName() {
        return "import_credential";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull ToolPreparation prepare(
            @NonNull Args args, PluginHost.@NonNull Invocation invocation) {
        return new ToolPreparation(
                new ToolPreparation.DisclosureIntent(args.visibility() == Visibility.PUBLIC),
                new JsonValue.ObjectValue(Map.of()));
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        var handle =
                vault.open(
                        Map.of(
                                "value",
                                args.value(),
                                "visibility",
                                args.visibility().name(),
                                "service",
                                args.service(),
                                "label",
                                args.label()));
        return agent.importValue(
                handle,
                args.value(),
                args.visibility() == Visibility.PUBLIC,
                args.service(),
                args.label());
    }

    /** Whether the primary model may read the imported value. */
    public enum Visibility {
        PRIVATE,
        PUBLIC
    }

    /** Exact value and nonsecret descriptive metadata. */
    public record Args(
            @NonNull @Doc("Exact source substring; include complete private-key delimiters.")
                    String value,
            @NonNull @Doc("PRIVATE by default; PUBLIC only for explicit model-readable disclosure.")
                    Visibility visibility,
            @NonNull @Doc("Short service name without secret material.") String service,
            @NonNull @Doc("Short nonsecret label.") String label) {}
}
