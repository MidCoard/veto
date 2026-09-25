package top.focess.veto.controller;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.session.QuoteCheckService;
import top.focess.veto.session.QuoteCheckService.Check;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/** Owner-authorized, bounded asynchronous quotation queries; no original records are changed. */
@RestController
public class QuoteCheckController {
    private final @NonNull SessionService sessions;
    private final @NonNull KeysteadVault vault;
    private final @NonNull QuoteCheckService quotes;
    private final @NonNull ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    Thread.ofVirtual().name("quote-check-", 0).factory());

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
     * Times out after 10 seconds; 503 when the bounded executor queue is full.
     */
    @PostMapping("/api/sessions/{name}/agents/{agent}/records/{turn}/quote-check")
    public @NonNull CompletableFuture<List<Check>> check(
            @PathVariable @NonNull String name,
            @PathVariable @NonNull String agent,
            @PathVariable int turn,
            @RequestBody @NonNull Request request) {
        String user = vault.currentUser();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var session =
                sessions.resolveByName(name, user)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String body = request.body();
        if (body == null || body.length() > 64_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            return CompletableFuture.supplyAsync(
                            () -> quotes.check(session.sessionId(), agent, turn, body), executor)
                    .orTimeout(10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Quotation checker is busy", exception);
        }
    }

    /** Stops the quote-check executor. */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    /** Quotation check payload: the quoted {@code body} text to verify. */
    public record Request(String body) {}
}
