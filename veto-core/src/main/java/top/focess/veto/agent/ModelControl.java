package top.focess.veto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.*;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;

/** One admitted model tool call; plugins choose feature policy, core verifies control and facts. */
final class ModelControl implements ControlHost {
    private final @NonNull ToolCallContext context;
    private final @NonNull VetoRequest request;
    private final @NonNull ToolEngine engine;
    private final @NonNull Set<String> whitelist;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull RequestEvidence evidence;
    private final boolean executionAllowed;
    private final @NonNull Object evidenceBoundary = new Object();

    ModelControl(
            @NonNull ToolCallContext context,
            @NonNull VetoRequest request,
            @NonNull ToolEngine engine,
            @NonNull Set<String> whitelist,
            @NonNull ObjectMapper mapper,
            @NonNull List<TurnRecord> history,
            @NonNull Object requestIdentity,
            String modelCallId,
            boolean executionAllowed) {
        this.context = context;
        this.request = request;
        this.engine = engine;
        this.whitelist = Set.copyOf(whitelist);
        this.mapper = mapper;
        this.executionAllowed = executionAllowed;
        this.evidence =
                new RequestEvidence(
                        requestIdentity,
                        evidenceBoundary,
                        request,
                        modelCallId,
                        history,
                        () ->
                                ToolCallContextHolder.get() == context
                                        && context.executionPermit()
                                                .callId()
                                                .equals(ToolCallContextHolder.currentCallId()));
    }

    private void check() {
        if (CapabilityAccess.require(ToolCapability.LOOP_CONTROL) != context)
            throw new SecurityException("Control belongs to another invocation");
    }

    public @NonNull List<Tool> tools() {
        check();
        return request.tools().stream()
                .map(
                        tool -> {
                            var definition = engine.resolveDefinition(tool.name());
                            var provenance = definition == null ? null : definition.provenance();
                            return new Tool(
                                    tool,
                                    ControlSubmissions.kindOf(definition),
                                    provenance == null ? null : provenance.pluginId(),
                                    provenance == null ? null : provenance.localId());
                        })
                .toList();
    }

    public void validateInputs(
            @NonNull String name, @NonNull JsonNode inputs, @NonNull Set<String> deferredPaths) {
        check();
        var advertised =
                request.tools().stream()
                        .filter(tool -> tool.name().equals(name))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Tool is not available in this request: " + name));
        if (!whitelist.contains(name))
            throw new IllegalArgumentException("Tool is not authorized: " + name);
        var definition = engine.resolveDefinition(name);
        if (definition instanceof LocalToolDefinition local)
            NativeToolArgumentValidator.validate(name, inputs, local.argsClass(), deferredPaths);
        else
            NativeToolArgumentValidator.validateAgainstSchema(
                    name, inputs, mapper.valueToTree(advertised.inputSchema()), deferredPaths);
    }

    public @NonNull SourceEvidence evidence() {
        check();
        return evidence;
    }

    public void execute(@NonNull PluginWork work) {
        check();
        if (!executionAllowed)
            throw new IllegalArgumentException(
                    "Nested execution is unavailable in this invocation");
        ToolCallContextHolder.transfer(new ToolCallContextHolder.ResponseDirective.Execute(work));
    }

    public void finish(@NonNull String message, SourceEvidence.@Nullable Receipt receipt) {
        check();
        if (message.isBlank())
            throw new IllegalArgumentException("Completion message must not be blank");
        if (receipt != null) evidence.finish(receipt);
        var citations =
                receipt == null ? null : RequestEvidence.citations(receipt, evidenceBoundary);
        ToolCallContextHolder.transfer(
                new ToolCallContextHolder.ResponseDirective.Finish(
                        new VetoResponse(null, null, message, citations),
                        receipt == null
                                ? null
                                : RequestEvidence.seal(receipt, evidenceBoundary, message)));
    }
}
