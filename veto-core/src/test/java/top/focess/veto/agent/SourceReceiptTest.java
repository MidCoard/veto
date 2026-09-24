package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolInvocationFixture;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.*;

class SourceReceiptTest {
    private static @NonNull VetoRequest request() {
        return new VetoRequest(
                "system",
                "fallback",
                List.of(),
                ProviderType.OPENAI,
                "model",
                "credential",
                LlmOptions.defaults(),
                List.of(ChatMessage.user("Launch Friday.").withSourceTurns(List.of(1))),
                null,
                null);
    }

    private static @NonNull List<SourceEvidence.Declaration> declaration() {
        return List.of(
                new SourceEvidence.Declaration(
                        "launch", List.of(new SourceEvidence.Selector(null, "Launch Friday."))));
    }

    @Test
    void hostReceiptsRejectForgeryOtherCallOtherRequestAndModifiedGeneratedOutput() {
        var task = new Object();
        var boundary = new Object();
        var active = new AtomicBoolean(true);
        var evidence =
                new RequestEvidence(
                        task,
                        boundary,
                        request(),
                        "model-one",
                        List.of(TurnRecord.userPrompt(1, "Launch Friday.")),
                        active::get);
        var receipt = evidence.inspect(declaration()).receipt();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> evidence.finish(new SourceEvidence.Receipt() {}));
        var other =
                new RequestEvidence(
                        task,
                        new Object(),
                        request(),
                        "model-two",
                        List.of(TurnRecord.userPrompt(1, "Launch Friday.")),
                        () -> true);
        assertThrows(ToolDocs.nonNullClass(SecurityException.class), () -> other.finish(receipt));
        var sealed = RequestEvidence.seal(receipt, boundary, "[Launch](cite:launch)");
        if (RequestEvidence.forRequest(sealed, task, "model-one", "[Launch](cite:launch)") == null)
            throw new AssertionError("Missing verified source");
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () ->
                        RequestEvidence.forRequest(
                                sealed, new Object(), "model-one", "[Launch](cite:launch)"));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () ->
                        RequestEvidence.forRequest(
                                sealed, task, "model-two", "[Launch](cite:launch)"));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> RequestEvidence.forRequest(sealed, task, "model-one", "different answer"));
        active.set(false);
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class), () -> evidence.finish(receipt));
    }

    @Test
    void workCanForwardEarlierGeneratedAnswerAfterPredicateButCannotReplayOrCrossWork() {
        var task = new Object();
        var boundary = new Object();
        var evidence =
                new RequestEvidence(
                        task,
                        boundary,
                        request(),
                        "answer-call",
                        List.of(TurnRecord.userPrompt(1, "Launch Friday.")),
                        () -> true);
        var receipt =
                RequestEvidence.seal(
                        evidence.inspect(declaration()).receipt(),
                        boundary,
                        "[Launch](cite:launch)");
        var work = new RequestEvidence.WorkSources();
        work.register(receipt);
        work.register(null); // A later predicate generation has no source receipt.
        var otherWork = new RequestEvidence.WorkSources();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> otherWork.consume(receipt, task, "answer-call", "[Launch](cite:launch)"));
        var returnedWork = new RequestEvidence.WorkSources();
        returnedWork.register(receipt);
        returnedWork.close();
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> returnedWork.consume(receipt, task, "answer-call", "[Launch](cite:launch)"));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> returnedWork.register(receipt));
        if (work.consume(receipt, task, "answer-call", "[Launch](cite:launch)") == null)
            throw new AssertionError("Missing verified source");
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> work.consume(receipt, task, "answer-call", "[Launch](cite:launch)"));
    }

    @Test
    void plainFinishCannotCreateSourceMetadataAndControlCannotEscapeCall() throws Exception {
        var call = new ToolCall("third_party_finish", Map.of(), "call");
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var permit =
                new ToolExecutionPermit(
                                call,
                                ToolCapability.LOOP_CONTROL,
                                null,
                                null,
                                Map.of(),
                                List.of(),
                                null,
                                DeployerPolicy.FULL_ACCESS,
                                Set.of(),
                                null)
                        .withCaller("agent", user, "owner", session);
        var context =
                new ToolCallContext(
                        "agent", user, "owner", session, ToolResultPresentationMode.BASIC, permit);
        @NonNull ToolEngine engine = mock();
        var control =
                new ModelControl(
                        context,
                        request(),
                        engine,
                        Set.of(),
                        new ObjectMapper(),
                        List.of(),
                        new Object(),
                        "model",
                        true);
        ToolCallContextHolder.set(context);
        try {
            ToolInvocationFixture.call(
                    call.callId(),
                    () -> {
                        assertThrows(
                                ToolDocs.nonNullClass(IllegalArgumentException.class),
                                () -> control.finish(" ", null));
                        control.finish("[fake](cite:invented)", null);
                        return true;
                    });
            var outcome = ToolCallContextHolder.drainResponse();
            if (!(outcome instanceof ToolCallContextHolder.ResponseDirective.Finish finish))
                throw new AssertionError("No finish");
            assertNull(finish.citations());
            assertNull(finish.response().citations());
            assertThrows(
                    ToolDocs.nonNullClass(SecurityException.class),
                    () -> control.finish("later", null));
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
