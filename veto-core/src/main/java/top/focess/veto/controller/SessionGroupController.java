package top.focess.veto.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.group.GroupHistoryStore;
import top.focess.veto.group.GroupHistoryView;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.group.LegacyGroupHistory;
import top.focess.veto.session.SessionRecordService;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

@RestController
public class SessionGroupController {
    private final @NonNull SessionService sessions;
    private final @NonNull SessionRecordService records;
    private final @NonNull GroupHistoryStore history;
    private final @NonNull GroupRegistry registry;
    private final @NonNull KeysteadVault vault;

    public SessionGroupController(
            @NonNull SessionService sessions,
            @NonNull SessionRecordService records,
            @NonNull GroupHistoryStore history,
            @NonNull GroupRegistry registry,
            @NonNull KeysteadVault vault) {
        this.sessions = sessions;
        this.records = records;
        this.history = history;
        this.registry = registry;
        this.vault = vault;
    }

    @GetMapping("/api/sessions/{name}/groups")
    public @NonNull List<GroupHistoryView> groups(@PathVariable @NonNull String name) {
        String user = vault.currentUser();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var cfg =
                sessions.resolveByName(name, user)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var durable = history.load(cfg.sessionId(), registry);
        var legacy =
                LegacyGroupHistory.read(
                        records.load(
                                        cfg.sessionId(),
                                        name,
                                        cfg.toolResultPresentation(),
                                        cfg.guidedEnabled())
                                .records());
        List<GroupHistoryView> result = new ArrayList<>(durable);
        for (int i = 0; i < legacy.size(); i++) {
            var old = legacy.get(i);
            var next =
                    legacy.stream()
                            .filter(
                                    g ->
                                            g.leaderId().equals(old.leaderId())
                                                    && g.createdAt().isAfter(old.createdAt()))
                            .map(GroupHistoryView::createdAt)
                            .min(Comparator.naturalOrder())
                            .orElse(null);
            boolean replaced =
                    durable.stream()
                            .anyMatch(
                                    g ->
                                            g.leaderId().equals(old.leaderId())
                                                    && !g.createdAt().isBefore(old.createdAt())
                                                    && (next == null
                                                            || g.createdAt().isBefore(next)));
            if (!replaced) result.add(old);
        }
        result.sort(Comparator.comparing(GroupHistoryView::createdAt));
        return List.copyOf(result);
    }
}
