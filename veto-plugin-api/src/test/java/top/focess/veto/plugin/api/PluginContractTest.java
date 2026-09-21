package top.focess.veto.plugin.api;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.plugin.contract.*;
import top.focess.veto.plugin.contribution.*;

class PluginContractTest {
    @Test
    void identitiesRejectRangesTraversalAndAmbiguousVersions() {
        assertEquals("top.focess.fixture", new PluginIdentity("top.focess.fixture", "0.1.0").id());
        for (String id : List.of("../fixture", "org/veto", "top.focess.veto..fixture", "ORG.veto"))
            assertThrows(IllegalArgumentException.class, () -> new PluginIdentity(id, "0.1.0"));
        for (String version : List.of("01.0.0", "^1.0.0", "1.0.0-beta", "../1.0.0", "1.0"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PluginIdentity("top.focess.fixture", version));
    }

    @Test
    void jsonDefensivelyCopiesAndDoesNotPrintText() {
        var source = new HashMap<String, JsonValue>();
        source.put("private-key", new JsonValue.StringValue("synthetic-private-text"));
        var object = new JsonValue.ObjectValue(source);
        source.clear();
        assertEquals(1, object.values().size());
        assertThrows(UnsupportedOperationException.class, () -> object.values().clear());
        assertFalse(object.toString().contains("private"));
        var privateValue = object.values().get("private-key");
        if (privateValue == null) throw new AssertionError("Value missing");
        assertFalse(privateValue.toString().contains("synthetic"));
        var children = new ArrayList<JsonValue>();
        children.add(object);
        var array = new JsonValue.ArrayValue(children);
        children.clear();
        assertEquals(1, array.values().size());
        assertThrows(UnsupportedOperationException.class, () -> array.values().clear());
    }

    @Test
    void jsonEnforcesAggregateBudgetsIncludingKeys() {
        assertThrows(
                IllegalArgumentException.class, () -> new JsonValue.StringValue("x".repeat(65537)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JsonValue.ObjectValue(
                                Map.of("key", new JsonValue.StringValue("x".repeat(65536)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JsonValue.ArrayValue(
                                java.util.Collections.nCopies(4096, JsonValue.NullValue.INSTANCE)));
        JsonValue nested = JsonValue.NullValue.INSTANCE;
        for (int depth = 0; depth < 32; depth++) nested = new JsonValue.ArrayValue(List.of(nested));
        JsonValue tooDeep = nested;
        assertThrows(
                IllegalArgumentException.class, () -> new JsonValue.ArrayValue(List.of(tooDeep)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JsonValue.NumberValue(new BigDecimal("1e999999")));
    }

    @Test
    void stagedContributionsAreImmutableAndResourcesStayPackageRelative() {
        for (String path :
                List.of("../secret.md", "/prompts/a.md", "prompts/../a.md", "prompts/a\\b.md"))
            assertThrows(IllegalArgumentException.class, () -> new PromptContribution(path));
        var registrations = new ArrayList<Contribution<?>>();
        registrations.add(
                Contribution.of(
                        StandardContributionPoints.CATEGORIES,
                        "text",
                        new ToolCategory("Text", "Text tools")));
        var contributions = new PluginContributions(registrations);
        registrations.clear();
        assertEquals(1, contributions.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> contributions.entries().clear());
    }

    @Test
    void failuresExposeOnlyFixedCodesAndCancellationIsExplicit() {
        Cancellation cancelled = () -> true;
        var failure = assertThrows(PluginFailure.class, cancelled::checkCancelled);
        assertEquals(PluginFailure.Code.CANCELLED, failure.code());
        assertEquals("CANCELLED", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
    }
}
