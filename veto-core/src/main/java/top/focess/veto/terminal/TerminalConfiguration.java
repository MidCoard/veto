package top.focess.veto.terminal;

import java.io.IOException;
import java.net.ServerSocket;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.transport.tcp.TcpConnection;

/** Creates the listener that Spring closes after its dependent IpcServer component stops. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true")
public class TerminalConfiguration {
    @Bean(destroyMethod = "close")
    @NonNull ServerSocket ipcListener(
            @Value("${veto.terminal.bind-address}") @NonNull String bindAddress)
            throws IOException {
        return TcpConnection.bind(bindAddress);
    }
}
