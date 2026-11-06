package top.focess.veto.bus;

import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import org.apache.tomcat.websocket.server.WsSci;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.servlet.DispatcherServlet;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

/**
 * Starts production WebSocket handlers on embedded Tomcat without unrelated application services.
 */
public final class TerminalWebSocketTestServer {
    private TerminalWebSocketTestServer() {}

    public static @NonNull String token(
            @NonNull AnnotationConfigServletWebServerApplicationContext context) {
        return context.getBean(LoginSessionManager.class)
                .createLoginSession(TestUsers.ALICE, "alice");
    }

    public static @NonNull String address(
            @NonNull AnnotationConfigServletWebServerApplicationContext context) {
        var server = context.getWebServer();
        if (server == null) throw new AssertionError("Missing test HTTP server");
        return "ws://127.0.0.1:" + server.getPort() + "/ws/veto/terminal";
    }

    public static @NonNull AnnotationConfigServletWebServerApplicationContext open(
            @NonNull CommandRegistry registry,
            @NonNull AgentService agents,
            @NonNull AgentSessions runtime,
            int port) {
        var context = new AnnotationConfigServletWebServerApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "terminal-test", Map.of("veto.terminal.enabled", "true")));
        context.registerBean(
                TomcatServletWebServerFactory.class,
                () -> {
                    var factory = new TomcatServletWebServerFactory(port);
                    factory.addContextCustomizers(
                            servlet ->
                                    servlet.addServletContainerInitializer(new WsSci(), Set.of()));
                    return factory;
                });
        context.registerBean(
                "dispatcher",
                ServletRegistrationBean.class,
                () -> new ServletRegistrationBean<>(new DispatcherServlet(context), "/"));
        context.registerBean(CommandRegistry.class, () -> registry);
        context.registerBean(AgentService.class, () -> agents);
        context.registerBean(AgentSessions.class, () -> runtime);
        context.registerBean(SessionService.class, () -> mock(SessionService.class));
        context.registerBean(SessionCommandService.class, () -> mock(SessionCommandService.class));
        context.registerBean(LoginSessionManager.class, LoginSessionManager::new);
        context.register(
                WebSocketServer.class,
                WebSocketConfig.class,
                VetoWebSocketAuthInterceptor.class,
                BusConfiguration.class);
        context.refresh();
        return context;
    }
}
