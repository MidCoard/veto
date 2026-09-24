package top.focess.veto.builtin.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.NetworkEgressCapability;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.*;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NetworkEgressTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;

@ToolSecurity(capability = ToolCapability.NETWORK_EGRESS, defaultDanger = Danger.DANGEROUS)
@ToolDoc(
        description = "Read GitHub repository information using an imported credential.",
        behavior = "After authorization, GET api.github.com once; bounded response, no redirects.",
        whenToUse = "Read a repository using an imported GitHub credential_ref.",
        whenNotToUse =
                "No plaintext or arbitrary URLs. Import approval does not authorize this request.",
        resultContract =
                "JSON with id, private, full_name and optional description/default_branch. Responses are filtered before returning. Errors do not include raw service bodies. Failures are plaintext: `Invalid repository: the repository owner or name is invalid.` (INVALID_REPOSITORY), `Credential unavailable: ...` (CREDENTIAL_UNAVAILABLE), `GitHub HTTP error: the repository request returned HTTP <status>.` (GITHUB_HTTP_ERROR), or `Authenticated read failed: the repository information could not be read.` (AUTHENTICATED_READ_FAILED).",
        errorsAndEdgeCases =
                "Invalid repository names, unavailable or wrong-owner credentials, HTTP errors, timeouts and oversized responses fail safely.",
        security =
                "The credential is used by reference only; its secret never enters arguments, results, or URLs. Requests go only to api.github.com.",
        resultFormats = {ToolResultFormat.JSON},
        returnExamples = {
            "{\"id\":12,\"private\":true,\"full_name\":\"example/project\"}",
            "{\"id\":1296269,\"private\":false,\"full_name\":\"octo-cat/hello-world\",\"description\":\"My first repository\",\"default_branch\":\"main\"}",
            "{\"id\":48151623,\"private\":false,\"full_name\":\"upstream-org/shared-library\",\"default_branch\":\"main\"}",
            "Invalid repository: the repository owner or name is invalid."
        },
        examples = {
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"example\",\"repositoryName\":\"project\"}",
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"octo-cat\",\"repositoryName\":\"hello-world\"}",
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"upstream-org\",\"repositoryName\":\"shared-library\"}",
            "{\"credentialRef\":\"cred_01234567-89ab-cdef-0123-456789abcdef\",\"repositoryOwner\":\"bad owner\",\"repositoryName\":\"project\"}"
        })
public final class ReadGitHubRepositoryTool
        implements NetworkEgressTool<ReadGitHubRepositoryTool.Args>, AutoCloseable {
    private final NetworkEgressCapability capability;
    private final @NonNull HttpClient client;

    public ReadGitHubRepositoryTool() {
        this(null, newClient());
    }

    public ReadGitHubRepositoryTool(@NonNull NetworkEgressCapability capability) {
        this(capability, newClient());
    }

    public ReadGitHubRepositoryTool(
            NetworkEgressCapability capability, @NonNull HttpClient client) {
        this.capability = capability;
        this.client = client;
    }

    private static @NonNull HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void close() {
        client.close();
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
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull NetworkEgressCapability restricted) {
        if (!args.repositoryOwner().matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
                || !args.repositoryName().matches("[A-Za-z0-9_.-]{1,100}")
                || args.repositoryName().equals(".")
                || args.repositoryName().equals(".."))
            return ToolErrors.failure(
                    ToolErrorCode.NETWORK.INVALID_REPOSITORY,
                    "Invalid repository: the repository owner or name is invalid.");
        var answer = new AtomicReference<String>();
        var status = new AtomicInteger();
        try (var lease = restricted.openImportedCredential("credentialRef", "github")) {
            lease.use(
                    value -> {
                        String secret = new String(value);
                        try {
                            var request =
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "https://api.github.com/repos/"
                                                                    + args.repositoryOwner()
                                                                    + "/"
                                                                    + args.repositoryName()))
                                            .timeout(Duration.ofSeconds(15))
                                            .header("Accept", "application/vnd.github+json")
                                            .header("X-GitHub-Api-Version", "2022-11-28")
                                            .header("Authorization", "Bearer " + secret)
                                            .GET()
                                            .build();
                            var response =
                                    client.send(
                                            request,
                                            HttpResponse.BodyHandlers.limiting(
                                                    HttpResponse.BodyHandlers.ofByteArray(),
                                                    1_048_576));
                            if (response.statusCode() != 200) {
                                status.set(response.statusCode());
                                return;
                            }
                            byte[] bytes = response.body();
                            if (bytes == null || bytes.length > 1_048_576)
                                throw new IllegalArgumentException("Invalid response size");
                            var mapper = new ObjectMapper();
                            var body = mapper.readTree(bytes);
                            if (body == null
                                    || !body.isObject()
                                    || !body.path("id").isIntegralNumber()
                                    || !body.path("full_name").isTextual()
                                    || !body.path("private").isBoolean())
                                throw new IllegalArgumentException("Invalid repository response");
                            Map<String, Object> safe = new LinkedHashMap<>();
                            safe.put("id", body.path("id").longValue());
                            safe.put("private", body.path("private").booleanValue());
                            for (String field :
                                    new String[] {"full_name", "description", "default_branch"})
                                if (body.path(field).isTextual())
                                    safe.put(
                                            field,
                                            body.path(field)
                                                    .asText()
                                                    .replace(secret, "[REDACTED_CREDENTIAL]"));
                            answer.set(mapper.writeValueAsString(safe));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } catch (Exception failure) {
                            /* Never expose response bodies, credentials or exception details. */
                        }
                    });
        } catch (RuntimeException failure) {
            return ToolErrors.failure(
                    ToolErrorCode.NETWORK.CREDENTIAL_UNAVAILABLE,
                    "Credential unavailable: the credential is unavailable for this operation.");
        }
        if (status.get() != 0)
            return ToolErrors.failure(
                    ToolErrorCode.NETWORK.GITHUB_HTTP_ERROR,
                    "GitHub HTTP error: the repository request returned HTTP "
                            + status.get()
                            + ".");
        var result = answer.get();
        return result == null
                ? ToolErrors.failure(
                        ToolErrorCode.NETWORK.AUTHENTICATED_READ_FAILED,
                        "Authenticated read failed: the repository information could not be read.")
                : result;
    }
}
