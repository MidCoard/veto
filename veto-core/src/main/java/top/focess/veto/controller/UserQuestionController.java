package top.focess.veto.controller;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.mcp.tools.UserQuestionRegistry;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/** Authenticated REST surface for pending ask_user question batches. */
@RestController
@RequestMapping("/api/sessions")
public final class UserQuestionController {

    private final @NonNull SessionService sessionService;
    private final @NonNull UserQuestionRegistry registry;
    private final @NonNull KeysteadVault vault;

    public UserQuestionController(
            @NonNull SessionService sessionService,
            @NonNull UserQuestionRegistry registry,
            @NonNull KeysteadVault vault) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.vault = vault;
    }

    @GetMapping("/{name}/questions")
    public @NonNull ResponseEntity<?> pending(@PathVariable @NonNull String name) {
        return ResponseEntity.ok(registry.pendingFor(requireAgentId(name)));
    }

    @PostMapping("/{name}/questions/{callId}")
    public @NonNull ResponseEntity<?> answer(
            @PathVariable @NonNull String name,
            @PathVariable @NonNull String callId,
            @RequestBody @NonNull AnswerRequest body) {
        if (!registry.answer(requireAgentId(name), callId, body.answers())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Question batch or answers are invalid");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/questions/{callId}/cancel")
    public @NonNull ResponseEntity<?> cancel(
            @PathVariable @NonNull String name, @PathVariable @NonNull String callId) {
        if (!registry.cancel(requireAgentId(name), callId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No pending question batch");
        }
        return ResponseEntity.noContent().build();
    }

    private @NonNull String requireAgentId(@NonNull String name) {
        String user = vault.currentUser();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return sessionService
                .primaryAgentIdFor(name, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record AnswerRequest(@NonNull Map<@NonNull String, @NonNull String> answers) {}
}
