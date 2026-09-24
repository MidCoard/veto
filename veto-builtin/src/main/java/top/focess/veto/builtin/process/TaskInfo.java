package top.focess.veto.builtin.process;

import java.time.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public record TaskInfo(
        @NonNull String taskId,
        @NonNull String agentId,
        @NonNull String command,
        @NonNull String cwd,
        @NonNull Instant startedAt,
        boolean alive,
        Integer exitCode,
        long pid,
        Instant finishedAt,
        UUID sessionId,
        @NonNull UUID taskInstanceId,
        String requestId) {

    public TaskInfo(
            @NonNull String taskId,
            @NonNull String agentId,
            @NonNull String command,
            @NonNull String cwd,
            @NonNull Instant startedAt,
            boolean alive,
            Integer exitCode,
            long pid,
            Instant finishedAt,
            UUID sessionId,
            @NonNull UUID taskInstanceId) {
        this(
                taskId,
                agentId,
                command,
                cwd,
                startedAt,
                alive,
                exitCode,
                pid,
                finishedAt,
                sessionId,
                taskInstanceId,
                null);
    }

    /** Convenience: elapsed seconds since start (0 if somehow negative). */
    public long uptimeSeconds() {
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        long seconds = Duration.between(startedAt, end).toSeconds();
        return Math.max(0, seconds);
    }
}
