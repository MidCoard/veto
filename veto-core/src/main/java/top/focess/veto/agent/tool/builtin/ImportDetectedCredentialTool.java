package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.CredentialImportCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.CredentialImportTool;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;

@Component
@ToolSecurity(capability = ToolCapability.CREDENTIAL_IMPORT, defaultDanger = Danger.DANGEROUS)
@ToolDoc(
        examples = {
            "{\"secret_ref\":\"s_0123456789abcdef0123456789abcdef\",\"service\":\"github\",\"label\":\"Project repository\"}",
            "{\"secret_ref\":\"s_fedcba9876543210fedcba9876543210\",\"service\":\"github\",\"label\":\"CI token for example/project\"}",
            "{\"secret_ref\":\"s_aabbccddeeff00112233445566778899\",\"service\":\"github\",\"label\":\"Release automation\"}",
            "{\"secret_ref\":\"s_0000000000000000000000000000000a\",\"service\":\"github\",\"label\":\"Unknown reference\"}"
        },
        returnExamples = {
            "{\"credential_ref\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"service\":\"github\",\"label\":\"Project repository\",\"status\":\"created\"}",
            "{\"credential_ref\":\"cred_9f8e7d6c-5b4a-4c3d-2e1f-0a9b8c7d6e5f\",\"service\":\"github\",\"label\":\"CI token for example/project\",\"status\":\"created\"}",
            "{\"credential_ref\":\"cred_1a2b3c4d-5e6f-4a5b-8c9d-0e1f2a3b4c5d\",\"service\":\"github\",\"label\":\"Release automation\",\"status\":\"created\"}",
            "Tool execution failed: Secret reference is unavailable"
        },
        description = "Import a detected reference into the owner's encrypted vault.",
        resultFormats = {ToolResultFormat.JSON},
        behavior =
                "After approval, save the captured value. Repeating reference/service/label returns the same credential.",
        whenToUse = "Store a registered SECRET_REF from this agent's session.",
        whenNotToUse =
                "No plaintext or invented references. Import does not authorize use or sharing.",
        resultContract = "JSON: credential_ref, label, service, status created; no secret value.",
        errorsAndEdgeCases =
                "Reject unknown/expired/cross-agent references, changed parameters or locked vaults. Retry the same import after storage failure.",
        security =
                "Secrets move by reference only; the stored value never appears in arguments, results, or URLs. Only github is currently supported.")
public final class ImportDetectedCredentialTool
        implements CredentialImportTool<ImportDetectedCredentialTool.Args> {
    private final @NonNull CredentialImportCapability capability;

    public ImportDetectedCredentialTool(@NonNull CredentialImportCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @Doc("Registered s_ reference inside a SECRET_REF marker; never plaintext.")
                    @NonNull String secret_ref,
            @Doc("Supported service identifier: github.") @NonNull String service,
            @Doc("Non-secret display label, 1 to 80 characters.") @NonNull String label) {}

    @Override
    public @NonNull String getName() {
        return "import_detected_credential";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull CredentialImportCapability credentialImportCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull CredentialImportCapability restricted) {
        return restricted.importDetected(args.secret_ref(), args.service(), args.label());
    }
}
