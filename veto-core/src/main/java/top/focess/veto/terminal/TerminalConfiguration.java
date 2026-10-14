package top.focess.veto.terminal;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zeromq.ZContext;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.transport.zmq.ZmqChannel;

/** Initializes enabled IPC resources and lets Spring destroy them in reverse dependency order. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true", matchIfMissing = true)
public class TerminalConfiguration {
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

    @Bean(initMethod = "start", destroyMethod = "stop")
    @NonNull IpcServer ipcServer(
            @NonNull CommandRegistry registry,
            @NonNull AgentSessions sessions,
            @NonNull SessionService sessionService,
            @NonNull SessionCommandService commands,
            @Qualifier("ipcTransport") ZmqChannel.@NonNull Server transport) {
        return new IpcServer(registry, transport, sessions, sessionService, commands);
    }
}
