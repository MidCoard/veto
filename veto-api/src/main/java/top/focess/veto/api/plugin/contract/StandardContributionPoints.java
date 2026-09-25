package top.focess.veto.api.plugin.contract;

import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.LlmProvider;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.service.ServiceRegistration;

/**
 * Host-defined public registration points. The catalog itself knows none of these types, and
 * registering at a point neither selects the contribution nor grants host authority.
 */
public final class StandardContributionPoints {
    private StandardContributionPoints() {}

    /** Agent profile configuration policies. */
    public static final @NonNull ContributionPoint<AgentConfiguration> AGENT_CONFIGURATION =
            new ContributionPoint<>(
                    new ContributionId("veto:agent-configuration"),
                    1,
                    AgentConfiguration.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Plugin-owned agent work inboxes. */
    public static final @NonNull ContributionPoint<AgentWorkSource> AGENT_WORK =
            new ContributionPoint<>(
                    new ContributionId("veto:agent-work"),
                    1,
                    AgentWorkSource.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Model transport providers. */
    public static final @NonNull ContributionPoint<LlmProvider> LLM_PROVIDERS =
            new ContributionPoint<>(
                    new ContributionId("veto:llm-providers"),
                    1,
                    ToolDocs.nonNullClass(LlmProvider.class),
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Named JSON service registrations. */
    public static final @NonNull ContributionPoint<ServiceRegistration> SERVICES =
            new ContributionPoint<>(
                    new ContributionId("veto:services"),
                    1,
                    ServiceRegistration.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Per-exchange model response policies. */
    public static final @NonNull ContributionPoint<ModelResponsePolicy> MODEL_RESPONSE =
            new ContributionPoint<>(
                    new ContributionId("veto:model-response"),
                    1,
                    ModelResponsePolicy.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Ordered session workflow hooks. */
    public static final @NonNull ContributionPoint<WorkflowHook> WORKFLOW =
            new ContributionPoint<>(
                    new ContributionId("veto:workflow"),
                    1,
                    WorkflowHook.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Trusted frontend modules and backend action handlers. */
    public static final @NonNull ContributionPoint<FrontendContribution> FRONTEND =
            new ContributionPoint<>(
                    new ContributionId("veto:frontend"),
                    1,
                    FrontendContribution.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Protection applied to view-file observations. */
    public static final @NonNull ContributionPoint<FileObservation> FILE_OBSERVATION =
            new ContributionPoint<>(
                    new ContributionId("veto:file-observation"),
                    1,
                    FileObservation.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Protection applied to user input. */
    public static final @NonNull ContributionPoint<InputProtection> INPUT_PROTECTION =
            new ContributionPoint<>(
                    new ContributionId("veto:input-protection"),
                    1,
                    InputProtection.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Protection applied while reading file content. */
    public static final @NonNull ContributionPoint<FileProtection> FILE_PROTECTION =
            new ContributionPoint<>(
                    new ContributionId("veto:file-protection"),
                    1,
                    FileProtection.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Permanent-data deletion participants. */
    public static final @NonNull ContributionPoint<DataLifecycle> DATA_LIFECYCLE =
            new ContributionPoint<>(
                    new ContributionId("veto:data-lifecycle"),
                    1,
                    ToolDocs.nonNullClass(DataLifecycle.class),
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Best-effort owner, session, and agent lifecycle notifications. */
    public static final @NonNull ContributionPoint<SessionLifecycle> SESSION_LIFECYCLE =
            new ContributionPoint<>(
                    new ContributionId("veto:session-lifecycle"),
                    1,
                    SessionLifecycle.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /**
     * Validates that every tool category refers to a registered category.
     *
     * @param catalog completed contribution catalog to validate
     */
    public static void validateToolCategories(@NonNull ContributionCatalog catalog) {
        var categories = new HashSet<ContributionId>();
        for (var entry : catalog.entries(CATEGORIES)) categories.add(entry.id());
        for (var entry : catalog.entries(TOOLS)) {
            if (!categories.containsAll(entry.implementation().categories()))
                throw new IllegalArgumentException("Unknown tool category");
        }
    }

    /** Portable schema-authored JSON tools. */
    public static final @NonNull ContributionPoint<Tool> TOOLS =
            new ContributionPoint<>(
                    new ContributionId("veto:tools"),
                    1,
                    Tool.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /**
     * Record-authored Java tools: NativeTool, AgentTool, or annotated CapabilityTool. The host uses
     * the same registration and execution path as bundled tools. The point id is retained for
     * existing Java plugins; it is not a tool kind.
     */
    public static final @NonNull ContributionPoint<CapabilityTool<?>> NATIVE_TOOLS =
            nativeToolsPoint();

    @SuppressWarnings("unchecked") // CapabilityTool.class is Class<CapabilityTool>, widened to <?>.
    private static @NonNull ContributionPoint<CapabilityTool<?>> nativeToolsPoint() {
        Class<CapabilityTool<?>> raw = (Class<CapabilityTool<?>>) (Class<?>) CapabilityTool.class;
        return new ContributionPoint<>(
                new ContributionId("veto:native-tools"),
                1,
                ToolDocs.nonNullClass(raw),
                ContributionPoint.Cardinality.MULTIPLE);
    }

    /** Tool presentation categories. */
    public static final @NonNull ContributionPoint<ToolCategory> CATEGORIES =
            new ContributionPoint<>(
                    new ContributionId("veto:tool-categories"),
                    1,
                    ToolCategory.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Static prompt resources. */
    public static final @NonNull ContributionPoint<PromptContribution> PROMPTS =
            new ContributionPoint<>(
                    new ContributionId("veto:prompts"),
                    1,
                    PromptContribution.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Ordered ordinary observation transforms. */
    public static final @NonNull ContributionPoint<ObservationMiddleware> OBSERVATION =
            new ContributionPoint<>(
                    new ContributionId("veto:observation-middleware"),
                    1,
                    ObservationMiddleware.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /**
     * Every standard point in a stable order. The host defines all of them so that querying an
     * unpopulated point returns an empty list instead of failing registration.
     */
    public static final @NonNull List<@NonNull ContributionPoint<?>> ALL =
            List.of(
                    AGENT_CONFIGURATION,
                    AGENT_WORK,
                    SERVICES,
                    LLM_PROVIDERS,
                    WORKFLOW,
                    MODEL_RESPONSE,
                    FRONTEND,
                    FILE_OBSERVATION,
                    INPUT_PROTECTION,
                    FILE_PROTECTION,
                    SESSION_LIFECYCLE,
                    DATA_LIFECYCLE,
                    TOOLS,
                    NATIVE_TOOLS,
                    CATEGORIES,
                    PROMPTS,
                    OBSERVATION);
}
