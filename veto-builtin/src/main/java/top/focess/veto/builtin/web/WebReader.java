package top.focess.veto.builtin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.agent.capability.NetworkEgressCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.plugin.agent.AgentHost;

/**
 * The feature journey: approved document, private reading, validated evidence, and settled exit.
 */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class WebReader {
    private final Supplier<AgentHost> host;
    private final ReaderConfig configuration;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebReader(Supplier<AgentHost> host, ReaderConfig configuration) {
        this.host = host;
        this.configuration = configuration;
    }

    public String read(String objective, NetworkEgressCapability network) {
        long started = System.nanoTime();
        try (var destination = network.openApprovedDestination("url")) {
            var document = new AtomicReference<WebReadSession>();
            var child =
                    host.get()
                            .isolate(
                                    configuration.spec(),
                                    runtime -> {
                                        destination.bind(runtime, "fetch_page");
                                        var session = new WebReadSession(runtime, destination);
                                        document.set(session);
                                        return session;
                                    });
            String encoded;
            try (child) {
                var session = document.get();
                if (session == null)
                    throw new IllegalStateException("Host did not create private tools");
                try {
                    var request = child.submit(objective);
                    long deadline = started + child.limits().timeout().toNanos();
                    var result =
                            request.result()
                                    .get(
                                            Math.max(1, deadline - System.nanoTime()),
                                            TimeUnit.NANOSECONDS);
                    if (System.nanoTime() >= deadline) throw new TimeoutException();
                    session.check();
                    var evidence = session.result();
                    if (!result.success() || evidence == null) {
                        if (child.budgetExhausted())
                            return ToolErrors.failure(
                                    ToolErrorCode.READER.READER_BUDGET,
                                    "Reader budget: the reader exhausted its execution budget without a validated result.");
                        return ToolErrors.failure(
                                ToolErrorCode.READER.READER_MODEL,
                                "Reader model: the reader ended without a validated result; check its configured model.");
                    }
                    try {
                        encoded = mapper.writeValueAsString(evidence);
                    } catch (Exception failure) {
                        return ToolErrors.failure(
                                ToolErrorCode.READER.READER_OUTPUT,
                                "Reader output: the reader result could not be encoded.");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return ToolErrors.failure(
                            ToolErrorCode.LIFECYCLE.CANCELLED,
                            "Cancelled: the web reader was cancelled.");
                } catch (TimeoutException failure) {
                    return ToolErrors.failure(
                            ToolErrorCode.READER.READER_TIMEOUT,
                            "Reader timeout: the web reader exceeded its time budget.");
                } catch (ExecutionException failure) {
                    return ToolErrors.failure(
                            ToolErrorCode.READER.READER_MODEL,
                            "Reader model: the reader ended without a validated result; check its configured model.");
                }
            }
            destination.publish(child);
            return encoded;
        }
    }
}
