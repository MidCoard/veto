package top.focess.veto.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Part-8 bridge between the {@link DeltaBroker} and the multi-client WebSocket transport
 * ({@link VetoWebSocketHandler}). Subscribes to <em>every</em> session's frame stream on startup
 * and forwards each serialized {@link DeltaFrame} to all connected WebSocket clients via {@link
 * VetoWebSocketHandler#broadcastRaw(String)}.
 *
 * <p>This closes the loop: {@code AgentRunner.emitMessage → DeltaBroker.publish → DeltaBusBridge →
 * VetoWebSocketHandler → connected clients}. The broker fans out per-session to its direct
 * subscribers (tests, other transports); this bridge is the production subscriber that reaches the
 * wire. A bad client send is logged and swallowed so one slow client cannot stall the agent's
 * virtual thread (the broker already isolates per-subscriber failures).
 */
@Component
public class DeltaBusBridge {

    private static final Logger log = LoggerFactory.getLogger(DeltaBusBridge.class);

    private final DeltaBroker broker;
    private final VetoWebSocketHandler handler;
    private final ObjectMapper mapper;
    private AutoCloseable subscription;

    public DeltaBusBridge(DeltaBroker broker, VetoWebSocketHandler handler, ObjectMapper mapper) {
        this.broker = broker;
        this.handler = handler;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        subscription =
                broker.subscribeAll(
                        frame -> {
                            try {
                                handler.broadcastRaw(frame.toJson(mapper));
                            } catch (RuntimeException e) {
                                log.warn(
                                        "DeltaBusBridge: failed to forward frame (kind={}, seq={})",
                                        frame.kind(),
                                        frame.sequence(),
                                        e);
                            }
                        });
    }

    @PreDestroy
    void stop() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
    }
}
