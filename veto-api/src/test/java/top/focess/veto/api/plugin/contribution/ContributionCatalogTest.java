package top.focess.veto.api.plugin.contribution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.api.plugin.contract.ToolCategory;
import top.focess.veto.api.plugin.contract.ToolContribution;

class ContributionCatalogTest {
    private static final ContributionSource BUILTIN =
            new ContributionSource("veto.core", "1", ContributionSource.Origin.BUILTIN);
    private static final ContributionSource PLUGIN =
            new ContributionSource("example.plugin", "1", ContributionSource.Origin.PLUGIN);
    private static final ContributionPoint<String> LABELS =
            new ContributionPoint<>(
                    new ContributionId("example:labels"),
                    1,
                    String.class,
                    ContributionPoint.Cardinality.MULTIPLE);

    private static ContributionCatalog.@NonNull Builder labels() {
        return new ContributionCatalog.Builder().define(LABELS, text -> {});
    }

    @Test
    void builtinAndPluginShareTheSameRegistrationPathAndHostAttributedOwnership() {
        var catalog =
                labels().stage(BUILTIN, List.of(Contribution.of(LABELS, "name", "builtin")))
                        .stage(PLUGIN, List.of(Contribution.of(LABELS, "name", "plugin")))
                        .freeze();
        assertEquals(
                List.of("example.plugin:name", "veto.core:name"),
                catalog.entries(LABELS).stream().map(e -> e.id().value()).toList());
        assertEquals(
                ContributionSource.Origin.PLUGIN,
                catalog.entries(LABELS).getFirst().source().origin());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries(LABELS).clear());
    }

    @Test
    void unknownPointRejectsWholeBatchWithoutPartialRegistration() {
        var unknown =
                new ContributionPoint<>(
                        new ContributionId("unknown:point"),
                        1,
                        String.class,
                        ContributionPoint.Cardinality.MULTIPLE);
        var builder = labels();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.stage(
                                PLUGIN,
                                List.of(
                                        Contribution.of(LABELS, "valid", "would leak"),
                                        Contribution.of(unknown, "invalid", "invalid"))));
        builder.stage(PLUGIN, List.of(Contribution.of(LABELS, "valid", "replacement")));
        assertEquals(
                List.of("replacement"),
                builder.freeze().entries(LABELS).stream()
                        .map(ContributionEntry::implementation)
                        .toList());
    }

    @Test
    void duplicateIdsAndDuplicateSourcesCannotOverwriteRegistrations() {
        var builder = labels();
        var entry = Contribution.of(LABELS, "same", "value");
        assertThrows(
                IllegalArgumentException.class, () -> builder.stage(PLUGIN, List.of(entry, entry)));
        builder.stage(PLUGIN, List.of(entry));
        assertThrows(IllegalArgumentException.class, () -> builder.stage(PLUGIN, List.of()));
        assertEquals(1, builder.freeze().entries(LABELS).size());
    }

    @Test
    void bothApiVersionAndRuntimeTypeMustMatch() {
        var newer =
                new ContributionPoint<>(
                        LABELS.id(), 2, String.class, ContributionPoint.Cardinality.MULTIPLE);
        var wrongType =
                new ContributionPoint<>(
                        LABELS.id(), 1, Integer.class, ContributionPoint.Cardinality.MULTIPLE);
        var builder = labels();
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.stage(PLUGIN, List.of(Contribution.of(newer, "newer", "value"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.stage(PLUGIN, List.of(Contribution.of(wrongType, "wrong", 1))));
        var catalog = builder.freeze();
        assertThrows(IllegalArgumentException.class, () -> catalog.entries(newer));
    }

    @Test
    void semanticValidationRejectsAnEntireSource() {
        var builder =
                new ContributionCatalog.Builder()
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
                                        Contribution.of(LABELS, "one", "valid"),
                                        Contribution.of(LABELS, "two", ""))));
        assertTrue(builder.freeze().entries(LABELS).isEmpty());
    }

    @Test
    void relativeOrderingIsStableAndIndependentOfDiscoveryOrder() {
        var first =
                new Contribution<>(
                        LABELS,
                        "z",
                        "first",
                        Set.of(new ContributionId("example.plugin:a")),
                        Set.of());
        var last = Contribution.of(LABELS, "a", "last");
        for (var batch : List.of(List.of(first, last), List.of(last, first))) {
            var catalog = labels().stage(PLUGIN, batch).freeze();
            assertEquals(
                    List.of("first", "last"),
                    catalog.entries(LABELS).stream()
                            .map(ContributionEntry::implementation)
                            .toList());
        }
    }

    @Test
    void cyclicMissingAndCrossPointOrderConstraintsFailBeforePublication() {
        var a =
                new Contribution<>(
                        LABELS, "a", "a", Set.of(new ContributionId("example.plugin:b")), Set.of());
        var b =
                new Contribution<>(
                        LABELS, "b", "b", Set.of(new ContributionId("example.plugin:a")), Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> labels().stage(PLUGIN, List.of(a, b)).freeze());
        assertThrows(
                IllegalArgumentException.class, () -> labels().stage(PLUGIN, List.of(a)).freeze());
        var other =
                new ContributionPoint<>(
                        new ContributionId("example:other"),
                        1,
                        String.class,
                        ContributionPoint.Cardinality.MULTIPLE);
        var builder = labels().define(other, value -> {});
        builder.stage(PLUGIN, List.of(a, Contribution.of(other, "b", "b")));
        assertThrows(IllegalArgumentException.class, builder::freeze);
    }

    @Test
    void requiredAndSingletonPointsAreHostDecisionsAndFailedStartsCanBeDiscarded() {
        var point =
                new ContributionPoint<>(
                        new ContributionId("example:required"),
                        1,
                        String.class,
                        ContributionPoint.Cardinality.SINGLE);
        var builder = new ContributionCatalog.Builder().define(point, value -> {}).require(point);
        assertThrows(IllegalArgumentException.class, builder::freeze);
        builder.stage(BUILTIN, List.of(Contribution.of(point, "implementation", "builtin")));
        builder.stage(PLUGIN, List.of(Contribution.of(point, "implementation", "plugin")));
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
                new ContributionPoint<>(
                        new ContributionId("another:domain"),
                        1,
                        NewDomainType.class,
                        ContributionPoint.Cardinality.MULTIPLE);
        var batch = new ArrayList<Contribution<?>>();
        batch.add(Contribution.of(point, "custom", new NewDomainType("custom")));
        var builder =
                new ContributionCatalog.Builder().define(point, value -> {}).stage(PLUGIN, batch);
        batch.clear();
        assertEquals("custom", builder.freeze().entries(point).getFirst().implementation().value());
    }

    @Test
    void middlewareUsesTheSameCatalogAndCannotOrderAcrossHostStages() throws Exception {
        var point = StandardContributionPoints.OBSERVATION;
        ObservationMiddleware prefix =
                (text, cancel) -> {
                    cancel.checkCancelled();
                    return "prefix:" + text;
                };
        ObservationMiddleware suffix = (text, cancel) -> text + ":suffix";
        var builder = new ContributionCatalog.Builder().define(point, middleware -> {});
        builder.stage(BUILTIN, List.of(Contribution.of(point, "prefix", prefix)));
        builder.stage(
                PLUGIN,
                List.of(
                        new Contribution<>(
                                point,
                                "suffix",
                                suffix,
                                Set.of(),
                                Set.of(new ContributionId("veto.core:prefix")))));
        var catalog = builder.freeze();
        String result = "protected";
        for (var entry : catalog.entries(point))
            result = entry.implementation().transform(result, () -> false);
        assertEquals("prefix:protected:suffix", result);
        assertThrows(
                PluginFailure.class,
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
        builder.stage(BUILTIN, List.of(Contribution.of(LABELS, "label", "label")));
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
                        Tool.Effect.COMPUTATION,
                        Set.of(new ContributionId("veto.core:missing")),
                        (args, cancellation) -> JsonValue.NullValue.INSTANCE);
        var builder =
                new ContributionCatalog.Builder()
                        .define(StandardContributionPoints.TOOLS, value -> {})
                        .define(StandardContributionPoints.CATEGORIES, value -> {})
                        .validateWith(StandardContributionPoints::validateToolCategories)
                        .stage(
                                PLUGIN,
                                List.of(
                                        Contribution.of(
                                                StandardContributionPoints.TOOLS,
                                                "example",
                                                tool)));
        assertThrows(IllegalArgumentException.class, builder::freeze);
        builder.stage(
                BUILTIN,
                List.of(
                        Contribution.of(
                                StandardContributionPoints.CATEGORIES,
                                "missing",
                                new ToolCategory("Example", "Example tools"))));
        assertEquals(1, builder.freeze().entries(StandardContributionPoints.TOOLS).size());
    }
}
