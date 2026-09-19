package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.NetworkEgressCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.*;

@Component
@ToolSecurity(capability = ToolCapability.NETWORK_EGRESS, defaultDanger = Danger.DANGEROUS)
@ToolDoc(
        description = "Read GitHub repository information using an imported credential.",
        behavior = "After authorization, GET api.github.com once; bounded response, no redirects.",
        whenToUse = "Read a repository using an imported GitHub credential_ref.",
        whenNotToUse =
                "No plaintext or arbitrary URLs. Import approval does not authorize this request.",
        resultContract =
                "JSON with id, private, full_name and optional description/default_branch. Responses are filtered before returning. Errors do not include raw service bodies.",
        errorsAndEdgeCases =
                "Invalid repository names, unavailable or wrong-owner credentials, HTTP errors, timeouts and oversized responses fail safely.",
        security =
                "The credential is used by reference only; its secret never enters arguments, results, or URLs. Requests go only to api.github.com.",
        resultFormats = {ToolResultFormat.JSON},
        returnExamples = {
            "{\"id\":12,\"private\":true,\"full_name\":\"example/project\"}",
            "{\"id\":1296269,\"private\":false,\"full_name\":\"octo-cat/hello-world\",\"description\":\"My first repository\",\"default_branch\":\"main\"}",
            "{\"id\":48151623,\"private\":false,\"full_name\":\"upstream-org/shared-library\",\"default_branch\":\"main\"}",
            "Invalid repository owner or name"
        },
        examples = {
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"example\",\"repositoryName\":\"project\"}",
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"octo-cat\",\"repositoryName\":\"hello-world\"}",
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"upstream-org\",\"repositoryName\":\"shared-library\"}",
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"bad owner\",\"repositoryName\":\"project\"}"
        })
public final class ReadGitHubRepositoryTool
        implements NetworkEgressTool<ReadGitHubRepositoryTool.Args> {
    private final @NonNull NetworkEgressCapability capability;

    public ReadGitHubRepositoryTool(@NonNull NetworkEgressCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @Doc("Imported credential_ref; never a plaintext token.") @NonNull String credentialRef,
            @Doc("GitHub repository owner login, not the credential owner.")
                    @NonNull String repositoryOwner,
            @Doc("Repository name, without a path or URL.") @NonNull String repositoryName) {}

    @Override
    public @NonNull String getName() {
        return "read_github_repository";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull NetworkEgressCapability networkEgressCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull NetworkEgressCapability restricted) {
        return restricted.readGitHubRepository(
                args.credentialRef(), args.repositoryOwner(), args.repositoryName());
    }
}
