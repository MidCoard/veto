package top.focess.veto.agent.web;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.intercept.SecretMasker;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.vault.KeysteadVault;

/** A fixed authenticated operation, never a general credential substitution proxy. */
@Component
public final class GitHubRepositoryReader {
    private final @NonNull KeysteadVault vault;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull HttpClient client;

    @Autowired
    public GitHubRepositoryReader(@NonNull KeysteadVault vault, @NonNull ObjectMapper mapper) {
        this(
                vault,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    GitHubRepositoryReader(
            @NonNull KeysteadVault vault,
            @NonNull ObjectMapper mapper,
            @NonNull HttpClient client) {
        this.vault = vault;
        this.mapper = mapper;
        this.client = client;
    }

    public @NonNull String read(
            @NonNull String credentialRef,
            @NonNull String repositoryOwner,
            @NonNull String repositoryName) {
        var context =
                CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "read_github_repository");
        Map<String, Object> approved = context.executionPermit().call().args();
        if (!credentialRef.equals(approved.get("credentialRef"))
                || !repositoryOwner.equals(approved.get("repositoryOwner"))
                || !repositoryName.equals(approved.get("repositoryName")))
            throw new SecurityException("Repository request differs from the approved binding");
        String owner = context.owner();
        if (owner == null || owner.isBlank() || context.sessionId() == null)
            return ToolErrors.failure(
                    "CREDENTIAL_UNAVAILABLE", "An active owned session is required");
        if (!repositoryOwner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
                || !repositoryName.matches("[A-Za-z0-9_.-]{1,100}")
                || repositoryName.equals(".")
                || repositoryName.equals(".."))
            return ToolErrors.failure("INVALID_REPOSITORY", "Invalid repository owner or name");
        AtomicReference<String> result = new AtomicReference<>();
        AtomicInteger httpError = new AtomicInteger();
        try {
            vault.withImportedCredential(
                    owner,
                    credentialRef,
                    "github",
                    value -> {
                        String credential = new String(value);
                        try {
                            HttpRequest request =
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "https://api.github.com/repos/"
                                                                    + repositoryOwner
                                                                    + "/"
                                                                    + repositoryName))
                                            .timeout(Duration.ofSeconds(15))
                                            .header("Accept", "application/vnd.github+json")
                                            .header("X-GitHub-Api-Version", "2022-11-28")
                                            .header("Authorization", "Bearer " + credential)
                                            .GET()
                                            .build();
                            var response =
                                    client.send(
                                            request,
                                            HttpResponse.BodyHandlers.limiting(
                                                    HttpResponse.BodyHandlers.ofByteArray(),
                                                    1_048_576));
                            if (response.statusCode() != 200) {
                                httpError.set(response.statusCode());
                                return;
                            }
                            var body = mapper.readTree(response.body());
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
                                    new String[] {"full_name", "description", "default_branch"}) {
                                var text = body.path(field);
                                if (text.isTextual())
                                    safe.put(
                                            field,
                                            SecretMasker.mask(
                                                    text.asText()
                                                            .replace(
                                                                    credential,
                                                                    "[REDACTED_CREDENTIAL]")));
                            }
                            result.set(mapper.writeValueAsString(safe));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } catch (Exception failure) {
                            // Do not expose service bodies, headers, credentials, or exception
                            // messages.
                        }
                    });
        } catch (RuntimeException failure) {
            return ToolErrors.failure(
                    "CREDENTIAL_UNAVAILABLE", "Credential is unavailable for this operation");
        }
        String answer = result.get();
        if (httpError.get() != 0)
            return ToolErrors.failure(
                    "GITHUB_HTTP_ERROR",
                    "GitHub repository request returned HTTP " + httpError.get());
        if (answer == null)
            return ToolErrors.failure(
                    "AUTHENTICATED_READ_FAILED", "Repository information could not be read");
        return answer;
    }
}
