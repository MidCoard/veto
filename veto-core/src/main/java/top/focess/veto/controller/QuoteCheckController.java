package top.focess.veto.controller;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.session.QuoteCheckService;
import top.focess.veto.session.QuoteCheckService.Check;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/**
 * Owner-authorized, MVC-managed asynchronous quotation queries; no original records are changed.
 */
@RestController
public class QuoteCheckController {
    private final @NonNull SessionService sessions;
    private final @NonNull KeysteadVault vault;
    private final @NonNull QuoteCheckService quotes;

    /** Creates the controller with session, vault, and quote-check collaborators. */
    public QuoteCheckController(
            @NonNull SessionService sessions,
            @NonNull KeysteadVault vault,
            @NonNull QuoteCheckService quotes) {
        this.sessions = sessions;
        this.vault = vault;
        this.quotes = quotes;
    }

    /**
     * Asynchronously checks a quotation against one turn of an agent's records in an owned session.
     * MVC schedules the callable and manages its 10-second request timeout.
     */
    @PostMapping("/api/sessions/{name}/agents/{agent}/records/{turn}/quote-check")
    public @NonNull WebAsyncTask<List<Check>> check(
            @PathVariable @NonNull String name,
            @PathVariable @NonNull String agent,
            @PathVariable int turn,
            @RequestBody @NonNull Request request) {
        UUID userId = vault.currentUser();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var session =
                sessions.resolveByName(name, userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String body = request.body();
        if (body == null || body.length() > 64_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new WebAsyncTask<>(
                10_000, () -> quotes.check(session.sessionId(), agent, turn, body));
    }

    /** Quotation check payload: the quoted {@code body} text to verify. */
    public record Request(String body) {}
}
