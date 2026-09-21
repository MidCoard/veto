package top.focess.veto.plugin.contract;

import java.util.HashSet;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contribution.ContributionCatalog;
import top.focess.veto.plugin.contribution.ContributionId;
import top.focess.veto.plugin.contribution.ContributionPoint;

/** Initial application contracts. The catalog itself knows none of these types. */
public final class StandardContributionPoints {
    private StandardContributionPoints() {}

    public static final @NonNull ContributionPoint<FrontendContribution> FRONTEND =
            new ContributionPoint<>(
                    new ContributionId("veto:frontend"),
                    1,
                    FrontendContribution.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    public static final @NonNull ContributionPoint<FileObservation> FILE_OBSERVATION =
            new ContributionPoint<>(
                    new ContributionId("veto:file-observation"),
                    1,
                    FileObservation.class,
                    ContributionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ContributionPoint<InputProtection> INPUT_PROTECTION =
            new ContributionPoint<>(
                    new ContributionId("veto:input-protection"),
                    1,
                    InputProtection.class,
                    ContributionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ContributionPoint<FileProtection> FILE_PROTECTION =
            new ContributionPoint<>(
                    new ContributionId("veto:file-protection"),
                    1,
                    FileProtection.class,
                    ContributionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ContributionPoint<SessionLifecycle> SESSION_LIFECYCLE =
            new ContributionPoint<>(
                    new ContributionId("veto:session-lifecycle"),
                    1,
                    SessionLifecycle.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    /** Semantic validator registered by the application, not hard-coded in the catalog. */
    public static void validateToolCategories(@NonNull ContributionCatalog catalog) {
        var categories = new HashSet<ContributionId>();
        for (var entry : catalog.entries(CATEGORIES)) categories.add(entry.id());
        for (var entry : catalog.entries(TOOLS)) {
            if (!categories.containsAll(entry.implementation().categories()))
                throw new IllegalArgumentException("Unknown tool category");
        }
    }

    public static final @NonNull ContributionPoint<Tool> TOOLS =
            new ContributionPoint<>(
                    new ContributionId("veto:tools"),
                    1,
                    Tool.class,
                    ContributionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ContributionPoint<ToolCategory> CATEGORIES =
            new ContributionPoint<>(
                    new ContributionId("veto:tool-categories"),
                    1,
                    ToolCategory.class,
                    ContributionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ContributionPoint<PromptContribution> PROMPTS =
            new ContributionPoint<>(
                    new ContributionId("veto:prompts"),
                    1,
                    PromptContribution.class,
                    ContributionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ContributionPoint<ObservationMiddleware> OBSERVATION =
            new ContributionPoint<>(
                    new ContributionId("veto:observation-middleware"),
                    1,
                    ObservationMiddleware.class,
                    ContributionPoint.Cardinality.MULTIPLE);
}
