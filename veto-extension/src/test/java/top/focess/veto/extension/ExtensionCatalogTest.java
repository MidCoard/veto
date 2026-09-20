package top.focess.veto.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.extension.contract.*;

class ExtensionCatalogTest {
    private static final ExtensionSource BUILTIN =
            new ExtensionSource("veto.core", "1", ExtensionSource.Origin.BUILTIN);
    private static final ExtensionSource PLUGIN =
            new ExtensionSource("example.plugin", "1", ExtensionSource.Origin.PLUGIN);
    private static final ExtensionPoint<String> LABELS =
            new ExtensionPoint<>(
                    new ExtensionId("example:labels"),
                    1,
                    String.class,
                    ExtensionPoint.Cardinality.MULTIPLE);

    private static ExtensionCatalog.@NonNull Builder labels() {
        return new ExtensionCatalog.Builder().define(LABELS, text -> {});
    }

    @Test
    void builtinAndPluginShareTheSameRegistrationPathAndHostAttributedOwnership() {
        var catalog =
                labels().stage(
                                BUILTIN,
                                List.of(ExtensionContribution.of(LABELS, "name", "builtin")))
                        .stage(PLUGIN, List.of(ExtensionContribution.of(LABELS, "name", "plugin")))
                        .freeze();
        assertEquals(
                List.of("example.plugin:name", "veto.core:name"),
                catalog.entries(LABELS).stream().map(e -> e.id().value()).toList());
        assertEquals(
                ExtensionSource.Origin.PLUGIN,
                catalog.entries(LABELS).getFirst().source().origin());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries(LABELS).clear());
    }

    @Test
    void unknownPointRejectsWholeBatchWithoutPartialRegistration() {
        var unknown =
                new ExtensionPoint<>(
                        new ExtensionId("unknown:point"),
                        1,
                        String.class,
                        ExtensionPoint.Cardinality.MULTIPLE);
        var builder = labels();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.stage(
                                PLUGIN,
                                List.of(
                                        ExtensionContribution.of(LABELS, "valid", "would leak"),
                                        ExtensionContribution.of(unknown, "invalid", "invalid"))));
        builder.stage(PLUGIN, List.of(ExtensionContribution.of(LABELS, "valid", "replacement")));
        assertEquals(
                List.of("replacement"),
                builder.freeze().entries(LABELS).stream()
                        .map(ExtensionEntry::implementation)
                        .toList());
    }

    @Test
    void duplicateIdsAndDuplicateSourcesCannotOverwriteRegistrations() {
        var builder = labels();
        var entry = ExtensionContribution.of(LABELS, "same", "value");
        assertThrows(
                IllegalArgumentException.class, () -> builder.stage(PLUGIN, List.of(entry, entry)));
        builder.stage(PLUGIN, List.of(entry));
        assertThrows(IllegalArgumentException.class, () -> builder.stage(PLUGIN, List.of()));
        assertEquals(1, builder.freeze().entries(LABELS).size());
    }

    @Test
    void bothApiVersionAndRuntimeTypeMustMatch() {
        var newer =
                new ExtensionPoint<>(
                        LABELS.id(), 2, String.class, ExtensionPoint.Cardinality.MULTIPLE);
        var wrongType =
                new ExtensionPoint<>(
                        LABELS.id(), 1, Integer.class, ExtensionPoint.Cardinality.MULTIPLE);
        var builder = labels();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.stage(
                                PLUGIN,
                                List.of(ExtensionContribution.of(newer, "newer", "value"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.stage(
                                PLUGIN, List.of(ExtensionContribution.of(wrongType, "wrong", 1))));
        var catalog = builder.freeze();
        assertThrows(IllegalArgumentException.class, () -> catalog.entries(newer));
    }

    @Test
    void semanticValidationRejectsAnEntireSource() {
        var builder =
                new ExtensionCatalog.Builder()
                        .define(
                                LABELS,
                                value -> {
                                    if (value.isBlank())
                                        throw new IllegalArgumentException("Empty label");
                                });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.stage(
                                PLUGIN,
                                List.of(
                                        ExtensionContribution.of(LABELS, "one", "valid"),
                                        ExtensionContribution.of(LABELS, "two", ""))));
        assertTrue(builder.freeze().entries(LABELS).isEmpty());
    }

    @Test
    void relativeOrderingIsStableAndIndependentOfDiscoveryOrder() {
        var first =
                new ExtensionContribution<>(
                        LABELS,
                        "z",
                        "first",
                        Set.of(new ExtensionId("example.plugin:a")),
                        Set.of());
        var last = ExtensionContribution.of(LABELS, "a", "last");
        for (var batch : List.of(List.of(first, last), List.of(last, first))) {
            var catalog = labels().stage(PLUGIN, batch).freeze();
            assertEquals(
                    List.of("first", "last"),
                    catalog.entries(LABELS).stream().map(ExtensionEntry::implementation).toList());
        }
    }

    @Test
    void cyclicMissingAndCrossPointOrderConstraintsFailBeforePublication() {
        var a =
                new ExtensionContribution<>(
                        LABELS, "a", "a", Set.of(new ExtensionId("example.plugin:b")), Set.of());
        var b =
                new ExtensionContribution<>(
                        LABELS, "b", "b", Set.of(new ExtensionId("example.plugin:a")), Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> labels().stage(PLUGIN, List.of(a, b)).freeze());
        assertThrows(
                IllegalArgumentException.class, () -> labels().stage(PLUGIN, List.of(a)).freeze());
        var other =
                new ExtensionPoint<>(
                        new ExtensionId("example:other"),
                        1,
                        String.class,
                        ExtensionPoint.Cardinality.MULTIPLE);
        var builder = labels().define(other, value -> {});
        builder.stage(PLUGIN, List.of(a, ExtensionContribution.of(other, "b", "b")));
        assertThrows(IllegalArgumentException.class, builder::freeze);
    }

    @Test
    void requiredAndSingletonPointsAreHostDecisionsAndFailedStartsCanBeDiscarded() {
        var point =
                new ExtensionPoint<>(
                        new ExtensionId("example:required"),
                        1,
                        String.class,
                        ExtensionPoint.Cardinality.SINGLE);
        var builder = new ExtensionCatalog.Builder().define(point, value -> {}).require(point);
        assertThrows(IllegalArgumentException.class, builder::freeze);
        builder.stage(
                BUILTIN, List.of(ExtensionContribution.of(point, "implementation", "builtin")));
        builder.stage(PLUGIN, List.of(ExtensionContribution.of(point, "implementation", "plugin")));
        assertThrows(IllegalArgumentException.class, builder::freeze);
        builder.discard(PLUGIN.namespace());
        var catalog = builder.freeze();
        assertEquals("builtin", catalog.entries(point).getFirst().implementation());
        assertThrows(IllegalStateException.class, () -> builder.discard(BUILTIN.namespace()));
        assertThrows(IllegalStateException.class, builder::freeze);
    }

    @Test
    void sourceBatchIsCopiedAndNewContractNeedsNoCatalogChanges() {
        record NewDomainType(String value) {}
        var point =
                new ExtensionPoint<>(
                        new ExtensionId("another:domain"),
                        1,
                        NewDomainType.class,
                        ExtensionPoint.Cardinality.MULTIPLE);
        var batch = new ArrayList<ExtensionContribution<?>>();
        batch.add(ExtensionContribution.of(point, "custom", new NewDomainType("custom")));
        var builder =
                new ExtensionCatalog.Builder().define(point, value -> {}).stage(PLUGIN, batch);
        batch.clear();
        assertEquals("custom", builder.freeze().entries(point).getFirst().implementation().value());
    }

    @Test
    void middlewareUsesTheSameCatalogAndCannotOrderAcrossHostStages() throws Exception {
        var point = StandardExtensionPoints.OBSERVATION;
        ObservationMiddleware prefix =
                (text, cancel) -> {
                    cancel.checkCancelled();
                    return "prefix:" + text;
                };
        ObservationMiddleware suffix = (text, cancel) -> text + ":suffix";
        var builder = new ExtensionCatalog.Builder().define(point, middleware -> {});
        builder.stage(BUILTIN, List.of(ExtensionContribution.of(point, "prefix", prefix)));
        builder.stage(
                PLUGIN,
                List.of(
                        new ExtensionContribution<>(
                                point,
                                "suffix",
                                suffix,
                                Set.of(),
                                Set.of(new ExtensionId("veto.core:prefix")))));
        var catalog = builder.freeze();
        String result = "protected";
        for (var entry : catalog.entries(point))
            result = entry.implementation().transform(result, () -> false);
        assertEquals("prefix:protected:suffix", result);
        assertThrows(
                ExtensionFailure.class,
                () ->
                        catalog.entries(point)
                                .getFirst()
                                .implementation()
                                .transform("protected", () -> true));
    }

    @Test
    void crossPointValidatorsRunBeforeSnapshotIsReturned() {
        var builder =
                labels().validateWith(
                                catalog -> {
                                    if (catalog.entries(LABELS).isEmpty())
                                        throw new IllegalArgumentException(
                                                "Missing application requirement");
                                });
        assertThrows(IllegalArgumentException.class, builder::freeze);
        builder.stage(BUILTIN, List.of(ExtensionContribution.of(LABELS, "label", "label")));
        assertEquals(1, builder.freeze().entries(LABELS).size());
    }

    @Test
    void toolsCannotReferenceAnUnregisteredCategory() {
        var schema = new JsonValue.ObjectValue(java.util.Map.of());
        var tool =
                new ToolContribution(
                        "Example",
                        schema,
                        schema,
                        ToolContribution.Effect.COMPUTATION,
                        Set.of(new ExtensionId("veto.core:missing")),
                        (args, cancellation) -> JsonValue.NullValue.INSTANCE);
        var builder =
                new ExtensionCatalog.Builder()
                        .define(StandardExtensionPoints.TOOLS, value -> {})
                        .define(StandardExtensionPoints.CATEGORIES, value -> {})
                        .validateWith(StandardExtensionPoints::validateToolCategories)
                        .stage(
                                PLUGIN,
                                List.of(
                                        ExtensionContribution.of(
                                                StandardExtensionPoints.TOOLS, "example", tool)));
        assertThrows(IllegalArgumentException.class, builder::freeze);
        builder.stage(
                BUILTIN,
                List.of(
                        ExtensionContribution.of(
                                StandardExtensionPoints.CATEGORIES,
                                "missing",
                                new ToolCategory("Example", "Example tools"))));
        assertEquals(1, builder.freeze().entries(StandardExtensionPoints.TOOLS).size());
    }
}
