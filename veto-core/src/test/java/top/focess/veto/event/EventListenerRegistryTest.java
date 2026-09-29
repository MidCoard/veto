package top.focess.veto.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.LifecycleEvent;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.OwnerOpenEvent;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionSource;

class EventListenerRegistryTest {
    @Test
    void inheritedHandlersAreExpandedBeforeDispatchInSpecificityOrder() {
        var calls = new ArrayList<String>();
        var source =
                new ContributionSource("demo.listener", "1.0.0", ContributionSource.Origin.PLUGIN);
        var catalog =
                new ContributionCatalog.Builder()
                        .define(StandardContributionPoints.LISTENERS, ignored -> {})
                        .stage(
                                source,
                                List.of(
                                        Contribution.of(
                                                StandardContributionPoints.LISTENERS,
                                                "probe",
                                                new Probe(calls))))
                        .freeze();
        var registry =
                EventListenerRegistry.build(
                        catalog,
                        (namespace, body) -> {
                            try {
                                body.run();
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        registry.broadcast(new OwnerOpenEvent("owner"), Set.of("demo.listener"));
        assertEquals(List.of("owner", "lifecycle", "event"), calls);
    }

    private static final class Probe extends Listener {
        private final @NonNull List<String> calls;

        private Probe(@NonNull List<String> calls) {
            this.calls = calls;
        }

        @EventHandler
        public void owner(@NonNull OwnerOpenEvent event) {
            calls.add("owner");
        }

        @EventHandler
        public void lifecycle(@NonNull LifecycleEvent event) {
            calls.add("lifecycle");
        }

        @EventHandler
        public void event(@NonNull Event event) {
            calls.add("event");
        }
    }
}
