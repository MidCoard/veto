package top.focess.veto.builtin.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SearchPolicyTest {
    @Test
    void domainMatchingIsLocaleIndependentAndBlockedPrecedesAllowed() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var kept = new SearchResult("kept", "https://WWW.EXAMPLE.IO/a", "");
            var blocked = new SearchResult("blocked", "https://blocked.EXAMPLE.IO/a", "");
            assertEquals(
                    List.of(kept),
                    SearchPolicy.apply(
                            List.of(blocked, kept),
                            new SearchOptions(
                                    List.of("EXAMPLE.IO"), List.of("BLOCKED.EXAMPLE.IO"), 1)));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void capAppliesAfterFilteringAndNonpositiveCapRetainsDefault() {
        var results =
                IntStream.range(0, 20)
                        .mapToObj(
                                index ->
                                        new SearchResult(
                                                "" + index, "https://example.org/" + index, ""))
                        .toList();
        assertEquals(3, SearchPolicy.apply(results, SearchOptions.of(3)).size());
        assertEquals(10, SearchPolicy.apply(results, SearchOptions.of(0)).size());
        assertEquals(
                List.of(),
                SearchPolicy.apply(results, new SearchOptions(List.of("other.org"), null, 3)));
    }
}
