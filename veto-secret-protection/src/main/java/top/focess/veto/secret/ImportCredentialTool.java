package top.focess.veto.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * In-process plugin tool executed through the host's internal tool state exactly like a core native
 * tool: the host reflects {@link Args} into the input schema, validates and deserializes the call,
 * and invokes {@link #execute(Args)}. The PRIVILEGED effect keeps every call behind approval-level
 * Gateway screening.
 */
@ToolSecurity(
        capability = ToolCapability.PRIVILEGED,
        defaultDanger = Danger.DANGEROUS,
        requiresSemanticScreening = true)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                """
                Import a session-registered SECRET_REF into the userId's encrypted vault after approval.\
                """,
        behavior =
                """
                Resolves the referenced secret candidate captured earlier in this session, checks the \
                approved tool invocation, and writes the credential into the userId's encrypted vault exactly \
                once. The plaintext secret never passes through the model or the tool arguments; only the \
                opaque reference, target service, and a human label are supplied.\
                """,
        whenToUse =
                """
                Use it when the user has explicitly approved persisting a detected credential that was masked \
                as a SECRET_REF during this session, so it can be reused later without re-exposing the \
                plaintext.\
                """,
        whenNotToUse =
                """
                Do not use it to store a secret the user pasted in plaintext, to import a reference the user \
                has not approved, or as a general key-value store. Leave unapproved candidates masked.\
                """,
        resultContract =
                """
                Success returns JSON with `credential_ref` (the stable vault handle), `service`, `label`, and \
                `status` (`created`). Failures return a plaintext diagnostic without echoing the secret \
                value.\
                """,
        errorsAndEdgeCases =
                """
                Import is idempotent per reference: re-importing the same SECRET_REF returns the existing \
                vault handle rather than duplicating it. An unknown, expired, or cross-session reference is \
                refused, and unavailable vault access surfaces as a failure. Cancellation before the \
                irreversible vault write aborts the import.\
                """,
        security =
                """
                Crosses the host trust boundary and writes to the encrypted vault; every call requires \
                explicit approval. Never accepts plaintext secret material, only an opaque session-scoped \
                reference.\
                """,
        examples = {
            "{\"secret_ref\":\"SECRET_REF_1\",\"service\":\"github\",\"label\":\"ci-token\"}",
            "{\"secret_ref\":\"SECRET_REF_2\",\"service\":\"aws\",\"label\":\"deploy-key\"}",
            "{\"secret_ref\":\"SECRET_REF_3\",\"service\":\"github\",\"label\":\"webhook-secret\"}"
        },
        returnExamples = {
            "{\"credential_ref\":\"cred_01HXX\",\"service\":\"github\",\"label\":\"ci-token\",\"status\":\"created\"}",
            "{\"credential_ref\":\"cred_01HXY\",\"service\":\"aws\",\"label\":\"deploy-key\",\"status\":\"created\"}",
            "{\"credential_ref\":\"cred_01HXZ\",\"service\":\"github\",\"label\":\"webhook-secret\",\"status\":\"created\"}"
        })
public final class ImportCredentialTool extends NativeTool<ImportCredentialTool.Args> {
    private static final @NonNull ObjectMapper RESULTS = new ObjectMapper();
    private final @NonNull SecretCandidateStore candidates;
    private final @NonNull VaultAccess vault;

    ImportCredentialTool(@NonNull SecretCandidateStore candidates, @NonNull VaultAccess vault) {
        this.candidates = candidates;
        this.vault = vault;
    }

    @Override
    public @NonNull ToolCapability getCapability() {
        return ToolCapability.PRIVILEGED;
    }

    @Override
    public @NonNull String getName() {
        return "import_detected_credential";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws Exception {
        var authorized =
                vault.open(
                        Map.of(
                                "secret_ref", args.secret_ref(),
                                "service", args.service(),
                                "label", args.label()));
        // Re-check after the (potentially blocking) host authorization so a caller that cancelled
        // while awaiting approval does not reach the irreversible vault write.
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Credential import cancelled");
        var receipt =
                candidates.importOnce(
                        authorized.scope(),
                        args.secret_ref(),
                        args.service(),
                        args.label(),
                        authorized);
        return RESULTS.writeValueAsString(
                new Result(receipt.credentialRef(), receipt.service(), receipt.label(), "created"));
    }

    /** Tool arguments; the host reflects this record into the input schema. */
    public record Args(
            @NonNull @Doc("Opaque SECRET_REF captured earlier in this session; never plaintext.")
                    String secret_ref,
            @NonNull @Doc("Target service the credential belongs to, e.g. github or aws.")
                    String service,
            @NonNull @Doc("Human-readable label stored alongside the vault entry.") String label) {}

    /** Tool result; the host serializes this record back to JSON. */
    public record Result(
            @NonNull String credential_ref,
            @NonNull String service,
            @NonNull String label,
            @NonNull String status) {}
}
