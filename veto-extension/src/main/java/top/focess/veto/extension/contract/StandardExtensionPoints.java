package top.focess.veto.extension.contract;

import org.jspecify.annotations.NonNull;

import top.focess.veto.extension.ExtensionCatalog;
import top.focess.veto.extension.ExtensionId;
import top.focess.veto.extension.ExtensionPoint;

import java.util.HashSet;

/** Initial application contracts. The catalog itself knows none of these types. */
public final class StandardExtensionPoints {
    private StandardExtensionPoints() {}

    public static final @NonNull ExtensionPoint<FrontendExtension> FRONTEND =
            new ExtensionPoint<>(
                    new ExtensionId("veto:frontend"),
                    1,
                    FrontendExtension.class,
                    ExtensionPoint.Cardinality.MULTIPLE);

    public static final @NonNull ExtensionPoint<ReferenceRenderer> REFERENCE_RENDERERS =
            new ExtensionPoint<>(
                    new ExtensionId("veto:reference-renderers"),
                    1,
                    ReferenceRenderer.class,
                    ExtensionPoint.Cardinality.MULTIPLE);

    public static final @NonNull ExtensionPoint<TextProtection> FILE_OBSERVATION =
            new ExtensionPoint<>(
                    new ExtensionId("veto:file-observation"),
                    1,
                    TextProtection.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ExtensionPoint<TextProtection> INPUT_PROTECTION =
            new ExtensionPoint<>(
                    new ExtensionId("veto:input-protection"),
                    1,
                    TextProtection.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ExtensionPoint<TextProtection> FILE_PROTECTION =
            new ExtensionPoint<>(
                    new ExtensionId("veto:file-protection"),
                    1,
                    TextProtection.class,
                    ExtensionPoint.Cardinality.MULTIPLE);

    /** Semantic validator registered by the application, not hard-coded in the catalog. */
    public static void validateToolCategories(@NonNull ExtensionCatalog catalog) {
        var categories = new HashSet<ExtensionId>();
        for (var entry : catalog.entries(CATEGORIES)) categories.add(entry.id());
        for (var entry : catalog.entries(TOOLS)) {
            if (!categories.containsAll(entry.implementation().categories()))
                throw new IllegalArgumentException("Unknown tool category");
        }
    }

    public static final @NonNull ExtensionPoint<ToolContribution> TOOLS =
            new ExtensionPoint<>(
                    new ExtensionId("veto:tools"),
                    1,
                    ToolContribution.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ExtensionPoint<ToolCategory> CATEGORIES =
            new ExtensionPoint<>(
                    new ExtensionId("veto:tool-categories"),
                    1,
                    ToolCategory.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ExtensionPoint<PromptContribution> PROMPTS =
            new ExtensionPoint<>(
                    new ExtensionId("veto:prompts"),
                    1,
                    PromptContribution.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
    public static final @NonNull ExtensionPoint<ObservationMiddleware> OBSERVATION =
            new ExtensionPoint<>(
                    new ExtensionId("veto:observation-middleware"),
                    1,
                    ObservationMiddleware.class,
                    ExtensionPoint.Cardinality.MULTIPLE);
}
