package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.LlmRateLimitException;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.integration.plugins.PluginLlmProviders;
import top.focess.veto.llm.egress.EgressEndpoint;
import top.focess.veto.llm.egress.LlmEgress;
import top.focess.veto.llm.provider.LLMProviderStrategy;

class DefaultUniformLLMCallerTest {
    @Test
    void plainTextIsAcceptedWithoutCorrectionOrRetry() {
        LLMProviderStrategy provider = mock(LLMProviderStrategy.class);
        when(provider.supports(ProviderType.DEEPSEEK)).thenReturn(true);
        when(provider.execute(any())).thenReturn(new VetoResponse(null, null, "Answer"));
        var caller = new DefaultUniformLLMCaller(List.of(provider), egressReturning("secret"));
        assertEquals("Answer", caller.call(request(ProviderType.DEEPSEEK)).message());
        verify(provider, times(1)).execute(any());
    }

    @Test
    void singleArgumentDefaultForwardsNullAndCanonicalCallPreservesSession() {
        var requests = new ArrayList<@NonNull VetoRequest>();
        var sessions = new ArrayList<@Nullable String>();
        var expected = new VetoResponse(null, null, "Answer");
        UniformLLMCaller caller =
                (request, sessionId) -> {
                    requests.add(request);
                    sessions.add(sessionId);
                    return expected;
                };
        var request = request(ProviderType.OPENAI);

        assertSame(expected, caller.call(request));
        assertSame(expected, caller.call(request, null));
        assertSame(expected, caller.call(request, "session-1"));

        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(observed -> observed == request));
        assertEquals(3, sessions.size());
        assertNull(sessions.get(0));
        assertNull(sessions.get(1));
        assertEquals("session-1", sessions.get(2));
    }

    @Test
    void optionalSessionSelectsUnscopedOrExactSessionProvider() {
        var provider = mock(LLMProviderStrategy.class);
        var plugins = mock(PluginLlmProviders.class);
        var expected = new VetoResponse(null, null, "Answer");
        when(provider.execute(any())).thenReturn(expected);
        when(plugins.require(ProviderType.OPENAI)).thenReturn(provider);
        when(plugins.require(ProviderType.OPENAI, "session-1")).thenReturn(provider);
        var implementation = new DefaultUniformLLMCaller(List.of(), egressReturning("secret"));
        implementation.attachPluginProviders(plugins);
        UniformLLMCaller caller = implementation;
        var request = request(ProviderType.OPENAI);

        assertSame(expected, caller.call(request));
        assertSame(expected, caller.call(request, null));
        assertSame(expected, caller.call(request, "session-1"));

        verify(plugins, times(2)).require(ProviderType.OPENAI);
        verify(plugins).require(ProviderType.OPENAI, "session-1");
        verifyNoMoreInteractions(plugins);
    }

    private @NonNull VetoRequest request(@NonNull ProviderType type) {
        return new VetoRequest(
                "sys",
                "user",
                List.of(),
                type,
                "model-x",
                "openai-key",
                LlmOptions.defaults(),
                List.of(),
                null,
                true,
                ResponseContract.ordinary());
    }

    private @NonNull LlmEgress egressReturning(@NonNull String apiKey) {
        LlmEgress egress = mock(LlmEgress.class);
        when(egress.resolve(any(), any(), any())).thenReturn(new EgressEndpoint(null, apiKey));
        return egress;
    }

    @Test
    void delegatesToSupportingProvider() {
        LLMProviderStrategy s1 = mock(LLMProviderStrategy.class);
        LLMProviderStrategy s2 = mock(LLMProviderStrategy.class);
        when(s1.supports(ProviderType.OPENAI)).thenReturn(false);
        when(s2.supports(ProviderType.OPENAI)).thenReturn(true);
        VetoResponse expected = new VetoResponse("thought", null, null);
        when(s2.execute(any(ResolvedRequest.class))).thenReturn(expected);
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s1, s2), egressReturning("secret"));
        assertEquals(expected, caller.call(request(ProviderType.OPENAI)));
        verify(s2).execute(any(ResolvedRequest.class));
        verify(s1, never()).execute(any());
    }

    @Test
    void throwsWhenNoProviderSupportsType() {
        LLMProviderStrategy s1 = mock(LLMProviderStrategy.class);
        when(s1.supports(any())).thenReturn(false);
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s1), egressReturning("secret"));
        assertThrows(
                ModelCapabilityException.class, () -> caller.call(request(ProviderType.ANTHROPIC)));
    }

    @Test
    void retriesRetryableFailureThenSucceeds() {
        LLMProviderStrategy s = mock(LLMProviderStrategy.class);
        when(s.supports(ProviderType.OPENAI)).thenReturn(true);
        VetoResponse expected = new VetoResponse("ok", null, null);
        when(s.execute(any(ResolvedRequest.class)))
                .thenThrow(new LlmRateLimitException("429", null))
                .thenReturn(expected);
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s), egressReturning("secret"));
        assertEquals(expected, caller.call(request(ProviderType.OPENAI)));
        verify(s, times(2)).execute(any(ResolvedRequest.class));
    }

    @Test
    void doesNotRetryNonRetryableFailure() {
        LLMProviderStrategy s = mock(LLMProviderStrategy.class);
        when(s.supports(ProviderType.OPENAI)).thenReturn(true);
        when(s.execute(any(ResolvedRequest.class)))
                .thenThrow(new ModelCapabilityException("permanent"));
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s), egressReturning("secret"));
        assertThrows(LlmException.class, () -> caller.call(request(ProviderType.OPENAI)));
        verify(s, times(1)).execute(any(ResolvedRequest.class));
    }
}
