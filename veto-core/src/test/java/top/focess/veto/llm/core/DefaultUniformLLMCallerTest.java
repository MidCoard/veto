package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.egress.EgressEndpoint;
import top.focess.veto.llm.egress.LlmEgress;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.LlmRateLimitException;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.provider.LLMProviderStrategy;

class DefaultUniformLLMCallerTest {
    private @NonNull VetoRequest request(@NonNull ProviderType type) {
        return new VetoRequest(
                "sys", "user", List.of(), type, "model-x", "openai-key", LlmOptions.defaults());
    }

    private @NonNull LlmEgress egressReturning(@NonNull String apiKey) {
        LlmEgress egress = mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LlmEgress.class));
        when(egress.resolve(any(), any(), any())).thenReturn(new EgressEndpoint(null, apiKey));
        return egress;
    }

    @Test
    void delegatesToSupportingProvider() {
        LLMProviderStrategy s1 =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LLMProviderStrategy.class));
        LLMProviderStrategy s2 =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LLMProviderStrategy.class));
        when(s1.supports(ProviderType.OPENAI)).thenReturn(false);
        when(s2.supports(ProviderType.OPENAI)).thenReturn(true);
        VetoResponse expected = new VetoResponse("thought", null, null, null, null);
        when(s2.execute(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        ResolvedRequest.class))))
                .thenReturn(expected);
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s1, s2), egressReturning("secret"));
        assertEquals(expected, caller.call(request(ProviderType.OPENAI)));
        verify(s2)
                .execute(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        ResolvedRequest.class)));
        verify(s1, never()).execute(any());
    }

    @Test
    void throwsWhenNoProviderSupportsType() {
        LLMProviderStrategy s1 =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LLMProviderStrategy.class));
        when(s1.supports(any())).thenReturn(false);
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s1), egressReturning("secret"));
        assertThrows(
                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(ModelCapabilityException.class),
                () -> caller.call(request(ProviderType.ANTHROPIC)));
    }

    @Test
    void retriesRetryableFailureThenSucceeds() {
        LLMProviderStrategy s =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LLMProviderStrategy.class));
        when(s.supports(ProviderType.OPENAI)).thenReturn(true);
        VetoResponse expected = new VetoResponse("ok", null, null, null, null);
        when(s.execute(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(ResolvedRequest.class))))
                .thenThrow(new LlmRateLimitException("429", null))
                .thenReturn(expected);
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s), egressReturning("secret"));
        assertEquals(expected, caller.call(request(ProviderType.OPENAI)));
        verify(s, times(2))
                .execute(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        ResolvedRequest.class)));
    }

    @Test
    void doesNotRetryNonRetryableFailure() {
        LLMProviderStrategy s =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LLMProviderStrategy.class));
        when(s.supports(ProviderType.OPENAI)).thenReturn(true);
        when(s.execute(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(ResolvedRequest.class))))
                .thenThrow(new ModelCapabilityException("permanent"));
        DefaultUniformLLMCaller caller =
                new DefaultUniformLLMCaller(List.of(s), egressReturning("secret"));
        assertThrows(
                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(LlmException.class),
                () -> caller.call(request(ProviderType.OPENAI)));
        verify(s, times(1))
                .execute(
                        any(
                                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                        ResolvedRequest.class)));
    }
}
