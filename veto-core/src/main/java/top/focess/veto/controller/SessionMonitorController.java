package top.focess.veto.controller;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

@RestController
public class SessionMonitorController {
    private final @NonNull SessionService sessions;
    private final @NonNull KeysteadVault vault;
    private final @NonNull MonitorService monitors;

    public SessionMonitorController(
            @NonNull SessionService sessions,
            @NonNull KeysteadVault vault,
            @NonNull MonitorService monitors) {
        this.sessions = sessions;
        this.vault = vault;
        this.monitors = monitors;
    }

    @GetMapping("/api/sessions/{name}/monitors")
    public @NonNull List<MonitorRecord> list(@PathVariable @NonNull String name) {
        String user = vault.currentUser();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var session =
                sessions.resolveByName(name, user)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return monitors.list(user, session.sessionId());
    }
}
