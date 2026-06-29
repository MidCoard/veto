package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the {@code @Component GroupOrchestrator} bean is wired against the <b>shared</b> {@link
 * GroupRegistry}/{@link Blackboard} beans (the ones {@link GroupSpawner} and the tools use).
 *
 * <p>The prior wiring gave the bean four constructors with no {@code @Autowired}, so Spring
 * selected the no-arg one — which built its own private {@code GroupRegistry}/{@code Blackboard}. A
 * group registered in the shared registry was therefore invisible to the bean's {@link
 * GroupOrchestrator#tick} (returned {@code null}), making the whole engine inert in production.
 */
@SpringBootTest
class GroupOrchestratorProductionWiringTest {

    @Autowired GroupOrchestrator orchestrator;
    @Autowired GroupRegistry registry;
    @Autowired Blackboard blackboard;

    @Test
    void componentBeanSeesGroupsRegisteredInTheSharedRegistry() {
        Group group =
                Group.create(
                        "leader-1",
                        "user-1",
                        "brief",
                        blackboard,
                        new ExecutionDag(UUID.randomUUID(), List.of()));
        UUID gid = group.groupId();
        registry.put(group);
        try {
            Group ticked = orchestrator.tick(gid);
            assertNotNull(
                    ticked,
                    "the @Component orchestrator must see groups in the SHARED registry (not a"
                            + " private copy)");
        } finally {
            registry.remove(gid);
        }
    }
}
