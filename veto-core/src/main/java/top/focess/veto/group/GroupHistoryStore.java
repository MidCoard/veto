package top.focess.veto.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.focess.veto.bus.SessionInvalidations;

@Service
public class GroupHistoryStore {
    private SessionInvalidations invalidations;

    @Autowired
    public void attachInvalidations(@NonNull SessionInvalidations invalidations) {
        this.invalidations = invalidations;
    }

    private static final @NonNull TypeReference<GroupHistoryView> VIEW_TYPE =
            new TypeReference<>() {};
    private final @NonNull GroupHistoryRepository repository;
    private final @NonNull ObjectMapper mapper;

    public GroupHistoryStore(
            @NonNull GroupHistoryRepository repository, @NonNull ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void save(@NonNull Group group) {
        var session = group.sessionId();
        if (session == null) return;
        var view =
                new GroupHistoryView(
                        group.groupId().toString(),
                        group.leaderId(),
                        group.contextBrief(),
                        group.state().name(),
                        group.createdAt(),
                        GroupHistoryView.nodes(group),
                        false,
                        true,
                        List.of(),
                        group.mates());
        try {
            repository.save(
                    new GroupHistoryEntity(session.toString(), mapper.writeValueAsString(view)));
            if (invalidations != null) invalidations.changed(session, "groups");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize group history", error);
        }
    }

    public @NonNull List<GroupHistoryView> load(
            @NonNull String sessionId, @NonNull GroupRegistry registry) {
        var latest = new LinkedHashMap<String, GroupHistoryView>();
        var changes = new LinkedHashMap<String, List<GroupHistoryView.Change>>();
        for (var row : repository.findBySessionIdOrderByRecordedAtAsc(sessionId)) {
            try {
                var view = mapper.readValue(row.getPayload(), VIEW_TYPE);
                latest.put(view.id(), view);
                changes.computeIfAbsent(view.id(), ignored -> new ArrayList<>())
                        .add(
                                new GroupHistoryView.Change(
                                        row.getRecordedAt(), view.state(), view.nodes()));
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Cannot read group history", error);
            }
        }
        return latest.values().stream()
                .map(
                        v ->
                                new GroupHistoryView(
                                                v.id(),
                                                v.leaderId(),
                                                v.brief(),
                                                v.state(),
                                                v.createdAt(),
                                                v.nodes(),
                                                false,
                                                registry.get(UUID.fromString(v.id())) != null,
                                                List.copyOf(
                                                        changes.getOrDefault(v.id(), List.of())),
                                                v.mates())
                                        .withoutRuntime())
                .toList();
    }

    public @NonNull List<GroupHistoryView> latestSnapshots(@NonNull String sessionId) {
        var latest = new LinkedHashMap<String, GroupHistoryView>();
        for (var row : repository.findBySessionIdOrderByRecordedAtAsc(sessionId)) {
            try {
                var view = mapper.readValue(row.getPayload(), VIEW_TYPE);
                latest.put(view.id(), view);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Cannot read recovery snapshot", error);
            }
        }
        return List.copyOf(latest.values());
    }
}
