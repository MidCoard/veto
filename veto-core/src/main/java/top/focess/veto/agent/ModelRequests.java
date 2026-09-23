package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRunner.LlmBinding;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ResponseSubmission;
import top.focess.veto.api.agent.workflow.GenerateAction;
import top.focess.veto.api.agent.workflow.Scope;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;

/** Constructs scoped requests using the current model and persona configuration. */
final class ModelRequests {
    private final @NonNull PromptCompiler promptCompiler;
    private final @NonNull Workspace workspace;
    private final @NonNull AgentPersona persona;
    private final @NonNull LlmBinding binding;
    private final @NonNull ToolResultPresentationMode toolResultPresentation;
    private final String owner;
    private final ModelTierRegistry guidedTierRegistry;
    private final @NonNull ResponseValidator responses;

    ModelRequests(
            @NonNull PromptCompiler compiler,
            @NonNull Workspace workspace,
            @NonNull AgentPersona persona,
            @NonNull LlmBinding binding,
            @NonNull ToolResultPresentationMode presentation,
            String owner,
            ModelTierRegistry tiers,
            @NonNull ResponseValidator responses) {
        this.promptCompiler = compiler;
        this.workspace = workspace;
        this.persona = persona;
        this.binding = binding;
        this.toolResultPresentation = presentation;
        this.owner = owner;
        this.guidedTierRegistry = tiers;
        this.responses = responses;
    }

    @NonNull VetoRequest completionRequest(
            @NonNull VetoRequest request, String tool, long remaining) {
        if (tool == null) return request;
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        messages.removeIf(
                message ->
                        message.promptSources().stream()
                                .anyMatch(
                                        source ->
                                                source.source().equals("runtime-completion.mdc")));
        messages.add(
                PromptCompiler.compileMessage(
                        "runtime-completion", Map.of("tool", tool, "remaining", remaining)));
        @NonNull VetoRequest scoped =
                new VetoRequest(
                        request.systemPrompt(),
                        request.userPrompt(),
                        request.tools().stream()
                                .filter(definition -> definition.name().equals(tool))
                                .toList(),
                        request.providerType(),
                        request.modelName(),
                        request.credentialKey(),
                        request.options(),
                        messages,
                        request.responseSchema(),
                        request.baseUrl(),
                        request.nativeToolsEnabled(),
                        ResponseContract.completion(tool, true));
        return scopeResponseRequest(scoped);
    }

    @NonNull VetoRequest generationRequest(
            @NonNull VetoRequest original,
            @NonNull GenerateAction generation,
            @NonNull Scope scope) {
        @NonNull LlmBinding selected = binding;
        String tier = generation.modelTier();
        if (tier != null) {
            var registry = guidedTierRegistry;
            String username = owner;
            if (registry == null || username == null)
                throw new IllegalStateException(
                        "Model tier override requires the session owner's model profile");
            var model =
                    registry.resolve(username, Nullness.requireNonNull(ModelTier.valueOf(tier)));
            selected =
                    new LlmBinding(
                            model.provider(),
                            model.model(),
                            model.credentialKey(),
                            new LlmOptions(
                                    model.temperature(),
                                    null,
                                    model.maxOutputTokens(),
                                    binding.options().timeout(),
                                    model.contextWindowTokens()),
                            binding.systemPromptBase(),
                            model.baseUrl());
        }
        LlmOptions options = selected.options();
        Double temperature = generation.temperature();
        if (temperature != null)
            options =
                    new LlmOptions(
                            temperature,
                            options.topP(),
                            options.maxTokens(),
                            options.timeout(),
                            options.contextWindowTokens());
        List<ChatMessage> messages = new ArrayList<>(original.messages());
        ChatMessage generated =
                PromptCompiler.compileMessage(
                        "runtime-generation",
                        Map.of(
                                "prompt",
                                generation.resolvePrompt(scope),
                                "inputs",
                                generation.resolveInputs(scope)));
        @NonNull String prompt = generated.content();
        messages.add(generated);
        boolean predicate = original.responseContract().mode() == ResponseContract.Mode.PREDICATE;
        boolean cited =
                !predicate && generation.responseMode() == GenerateAction.ResponseMode.CITATIONS;
        var tools =
                original.tools().stream()
                        .filter(
                                t ->
                                        cited
                                                && responses.submissionKind(t.name())
                                                        == ResponseSubmission.Kind.ANSWER)
                        .toList();
        if (cited && tools.isEmpty())
            throw new IllegalStateException(
                    "Citation generation requires an available answer submission tool");
        @NonNull VetoRequest scoped =
                new VetoRequest(
                        original.systemPrompt(),
                        prompt,
                        tools,
                        selected.provider(),
                        selected.model(),
                        selected.credentialKey(),
                        options,
                        messages,
                        original.responseSchema(),
                        selected.baseUrl(),
                        cited,
                        original.responseContract());
        return scopeResponseRequest(scoped);
    }

    @NonNull VetoRequest scopeResponseRequest(@NonNull VetoRequest request) {
        return promptCompiler.scopeRequest(
                request, persona, workspace, binding.systemPromptBase(), toolResultPresentation);
    }

    @NonNull CompiledPrompt compilePrompt(
            @NonNull List<TurnRecord> sourceHistory,
            boolean scopedInvocation,
            double correctionFactor,
            @NonNull ChatMessage recoveryContext) {
        // Generation/predicate calls first assemble history, then rebuild the system with their
        // restricted tool manifest. The dispatch guard budgets that final request and selected
        // model; budgeting this temporary full manifest could reject an otherwise fitting call.
        Long inputBudgetOverride = null;
        if (scopedInvocation) inputBudgetOverride = Long.MAX_VALUE;
        else if (binding.options().contextWindowTokens() != null)
            inputBudgetOverride = binding.options().inputBudget();
        return promptCompiler.compile(
                persona,
                workspace,
                binding.systemPromptBase(),
                sourceHistory,
                correctionFactor,
                toolResultPresentation,
                inputBudgetOverride,
                recoveryContext);
    }

    @NonNull VetoRequest buildRequest(@NonNull CompiledPrompt compiled) {
        List<ChatMessage> messages = new ArrayList<>(compiled.messages());
        @NonNull LlmBinding b = binding;
        return new VetoRequest(
                compiled.systemMessage(),
                messages.get(messages.size() - 1).content(),
                compiled.tools(),
                b.provider(),
                b.model(),
                b.credentialKey(),
                b.options(),
                messages,
                compiled.responseSchema(),
                b.baseUrl());
    }

    @NonNull VetoRequest injectSchemaRejection(
            @NonNull VetoRequest request, @NonNull ModelSchemaException e) {
        List<ChatMessage> augmented = new ArrayList<>(request.messages());
        augmented.add(
                PromptCompiler.compileMessage(
                        "runtime-schema-rejection",
                        Map.of(
                                "error",
                                String.valueOf(e.getMessage()),
                                "expected",
                                getExpectedDescription(request))));
        return new VetoRequest(
                request.systemPrompt(),
                request.userPrompt(),
                request.tools(),
                request.providerType(),
                request.modelName(),
                request.credentialKey(),
                request.options(),
                augmented,
                request.responseSchema(),
                request.baseUrl(),
                request.nativeToolsEnabled(),
                request.responseContract());
    }

    @NonNull String getExpectedDescription(@NonNull VetoRequest request) {
        return PromptCompiler.compileText(
                "runtime-expected", request.responseContract().promptData(request));
    }

    @NonNull VetoRequest compactionRequest(
            @NonNull ChatMessage systemPrompt, @NonNull ChatMessage userPrompt) {
        return new VetoRequest(
                systemPrompt.content(),
                userPrompt.content(),
                List.of(),
                binding.provider(),
                binding.model(),
                binding.credentialKey(),
                binding.options(),
                List.of(systemPrompt, userPrompt),
                null,
                binding.baseUrl());
    }
}
