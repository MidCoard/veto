package top.focess.veto.group;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the {@link GroupOrchestrator} over every active group on a fixed cadence. Without this,
 * nothing in production calls {@link GroupOrchestrator#tick} — a spawned group's DAG would never
 * advance past the initial dispatch (Mates post results to the Blackboard, but only a tick ingests
 * them). Mates poll the Blackboard themselves for their own {@code TASK_DISPATCH}; this scheduler
 * is the Leader side of the pump.
 *
 * <p>Each tick is per-group serialized inside the orchestrator, so a single scheduler thread is
 * safe. The cadence defaults to 1s and is tunable via {@code veto.group.tick.interval-ms}. A group
 * reaches {@code COMPLETED} when all nodes verify; the scheduler then stops ticking it while the
 * Leader inspects results and calls {@code disband_group} to reverse-transform.
 */
@Component
public class GroupTickScheduler {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.group.GroupTickScheduler");

    private final @NonNull GroupOrchestrator orchestrator;
    private final @NonNull GroupRegistry registry;

    public GroupTickScheduler(
            @NonNull GroupOrchestrator orchestrator, @NonNull GroupRegistry registry) {
        this.orchestrator = orchestrator;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${veto.group.tick.interval-ms}")
    public void tickActiveGroups() {
        Map<UUID, Group> snapshot = registry.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        for (Group group : snapshot.values()) {
            if (!group.isActive()) {
                continue;
            }
            try {
                orchestrator.tick(group.groupId());
            } catch (RuntimeException e) {
                // One group's tick failure must not stall the others or kill the scheduler.
                log.warn("GroupTickScheduler: tick failed for group {}", group.groupId(), e);
            }
        }
    }
}
