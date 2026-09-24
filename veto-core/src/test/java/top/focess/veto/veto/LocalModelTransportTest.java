package top.focess.veto.veto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.agent.tool.ToolDocs;

class LocalModelTransportTest {
    @Test
    void cancellingLocalCompletionCancelsThePendingHttpRequest() {
        @NonNull SlmConfiguration config = mock();
        @NonNull GBNFGrammarEngine grammars = mock();
        @NonNull HttpClient http = mock();
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(http.sendAsync(
                        any(ToolDocs.nonNullClass(HttpRequest.class)),
                        ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(pending);
        var bridge = new LlamaCppBridge(config, grammars);
        ReflectionTestUtils.setField(bridge, "httpClient", http);
        ReflectionTestUtils.setField(bridge, "available", true);
        ReflectionTestUtils.setField(bridge, "serverPort", 8080);
        var result = bridge.inferWithGrammar("compiled content", "root ::= \"x\"");
        result.cancel(true);
        assertTrue(pending.isCancelled());
        verifyNoInteractions(grammars);
    }
}
