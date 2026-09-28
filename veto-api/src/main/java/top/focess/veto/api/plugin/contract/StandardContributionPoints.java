package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.agent.tool.RemoteTool;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.llm.LlmProvider;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.ProtocolPointDefinition;
import top.focess.veto.api.plugin.service.ServiceRegistration;

import java.util.HashSet;
import java.util.List;

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
    public static final @NonNull ContributionPoint<AgentInbox> AGENT_INBOX =
            new ContributionPoint<>(
                    new ContributionId("veto:agent-inbox"),
                    1,
                    AgentInbox.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Model transport providers. */
    public static final @NonNull ContributionPoint<LlmProvider> LLM_PROVIDERS =
            new ContributionPoint<>(
                    new ContributionId("veto:llm-providers"),
                    1,
                    LlmProvider.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Named JSON service registrations. */
    public static final @NonNull ContributionPoint<ServiceRegistration> SERVICES =
            new ContributionPoint<>(
                    new ContributionId("veto:services"),
                    1,
                    ServiceRegistration.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Definitions of JSON contribution points supplied by plugins. */
    public static final @NonNull ContributionPoint<ProtocolPointDefinition> CONTRIBUTIONS =
            new ContributionPoint<>(
                    new ContributionId("veto:contributions"),
                    1,
                    ProtocolPointDefinition.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Per-exchange model response policies. */
    public static final @NonNull ContributionPoint<ModelResponsePolicy> MODEL_RESPONSE =
            new ContributionPoint<>(
                    new ContributionId("veto:model-response"),
                    1,
                    ModelResponsePolicy.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /**
     * Event listeners subscribed to host-dispatched workflow and lifecycle events. This is the
     * unified replacement for the former workflow-hook point and for the former best-effort
     * session-lifecycle point: a listener's {@code @EventHandler} methods are compiled once at
     * registration and dispatched in {@link top.focess.veto.api.event.EventPriority} order under
     * the contributing plugin's admission. Required permanent-data deletion stays on {@link
     * #DATA_LIFECYCLE}, which is a transactional participant rather than a notification.
     */
    public static final @NonNull ContributionPoint<Listener> LISTENERS =
            new ContributionPoint<>(
                    new ContributionId("veto:listeners"),
                    1,
                    Listener.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Trusted frontend modules and backend action handlers. */
    public static final @NonNull ContributionPoint<FrontendContribution> FRONTEND =
            new ContributionPoint<>(
                    new ContributionId("veto:frontend"),
                    1,
                    FrontendContribution.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Permanent-data deletion participants. */
    public static final @NonNull ContributionPoint<DataLifecycle> DATA_LIFECYCLE =
            new ContributionPoint<>(
                    new ContributionId("veto:data-lifecycle"),
                    1,
                    DataLifecycle.class,
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
            if (entry.implementation() instanceof RemoteTool tool
                    && !categories.containsAll(tool.categories()))
                throw new IllegalArgumentException("Unknown tool category");
        }
    }

    /**
     * All plugin tools: record-authored {@code AgentTool}/{@code NativeTool} implementations and
     * portable {@link RemoteTool} implementations share one registration group.
     */
    public static final @NonNull ContributionPoint<Object> TOOLS =
            new ContributionPoint<>(
                    new ContributionId("veto:tools"),
                    1,
                    Object.class,
                    ContributionPoint.Cardinality.MULTIPLE);

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
                    AGENT_INBOX,
                    SERVICES,
                    CONTRIBUTIONS,
                    LLM_PROVIDERS,
                    LISTENERS,
                    MODEL_RESPONSE,
                    FRONTEND,
                    DATA_LIFECYCLE,
                    TOOLS,
                    CATEGORIES,
                    PROMPTS,
                    OBSERVATION);
}
