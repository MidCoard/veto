package top.focess.veto.terminal;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.zeromq.ZContext;
import top.focess.veto.agent.AgentService;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.contract.Frame;
import top.focess.veto.transport.zmq.ZmqChannel;

/** Initializes enabled IPC resources and lets Spring destroy them in reverse dependency order. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true", matchIfMissing = true)
public class TerminalConfiguration {
    // The subscription exists before the server; this callback target avoids a bean dependency
    // cycle.
    private volatile IpcServer server;

    @Bean(destroyMethod = "close")
    @NonNull ZContext ipcContext() {
        return new ZContext();
    }

    @Bean(destroyMethod = "close")
    ZmqChannel.@NonNull Server ipcTransport(
            @Qualifier("ipcContext") @NonNull ZContext context,
            @Value("${veto.terminal.bind-address}") @NonNull String bindAddress) {
        return ZmqChannel.Server.bindRouter(context, bindAddress);
    }

    @Bean(destroyMethod = "close")
    @DependsOn("ipcTransport")
    @NonNull AutoCloseable ipcSubscription(@NonNull DeltaBroker broker) {
        return broker.subscribeAll(this::publishAction);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @DependsOn("ipcSubscription")
    @NonNull IpcServer ipcServer(
            @NonNull CommandRegistry registry,
            @NonNull AgentService agentService,
            @Qualifier("ipcTransport") ZmqChannel.@NonNull Server transport) {
        var endpoint = new IpcServer(registry, agentService, transport);
        server = endpoint;
        return endpoint;
    }

    private void publishAction(Frame.@NonNull ScopedFrame frame) {
        var endpoint = server;
        if (endpoint != null) endpoint.publishAction(frame);
    }
}
