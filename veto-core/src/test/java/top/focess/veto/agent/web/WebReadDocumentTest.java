package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolExecutionException;

class WebReadDocumentTest {
    @Test
    void preservesShortBlocksAndLateTextWithoutCrossingParagraphBoundaries() {
        var document =
                document(
                        "text/html",
                        "<main><h1>Reference</h1>"
                                + "<p>Small paragraph.</p>".repeat(2500)
                                + "<pre>  code\n    indentation</pre><h2>Contacts</h2><p>author@example.org</p></main>",
                        false);
        assertFalse(document.truncated());
        assertEquals(2504, document.outline().size());
        var matches = document.find("author@example.org");
        assertEquals(1, matches.size());
        assertEquals(
                "author@example.org",
                document.read(List.of(matches.getFirst().id())).getFirst().text());
        var code = document.find("indentation").getFirst();
        assertEquals("code\n    indentation", document.read(List.of(code.id())).getFirst().text());
    }

    @Test
    void longHtmlKeepsAddressAtTheEndAvailableWithoutReadingThePrefix() {
        String html =
                "<main><h1>Reference</h1>"
                        + ("<p>" + "Unrelated specification text. ".repeat(30) + "</p>").repeat(600)
                        + "<h2>Authors' Addresses</h2><p>Mark Example: author@example.org</p></main>";
        var document = document("text/html", html, false);
        var matches = document.find("author@example.org");
        assertEquals(1, matches.size());
        String id = matches.getFirst().id();
        assertEquals(
                "Mark Example: author@example.org", document.read(List.of(id)).getFirst().text());
        assertTrue(document.inspected().isEmpty());
        document.recordInspection(List.of(id));
        assertEquals("Authors' Addresses", document.evidence(id).section());
        assertFalse(document.truncated());
        assertFalse(document.fullyRead());
    }

    @Test
    void preservesDivTextAndResolvedHrefBesideHeadings() {
        WebReadDocument document =
                document(
                        "text/html",
                        """
                <main><h1>Configuration</h1><div>Timeout uses seconds.</div>
                <p>See <a href="/reference#timeout">timeout reference</a> for exceptions.</p></main>
                """,
                        false);
        List<WebReadDocument.Segment> segments =
                document.read(document.outline().stream().map(WebReadDocument.Entry::id).toList());
        document.recordInspection(
                document.outline().stream().map(WebReadDocument.Entry::id).toList());
        String text =
                String.join("\n", segments.stream().map(WebReadDocument.Segment::text).toList());
        assertTrue(text.contains("Timeout uses seconds."));
        assertTrue(text.contains("timeout reference (https://example.com/reference#timeout)"));
        assertEquals(3, segments.size());
        assertTrue(document.fullyRead());
    }

    @Test
    void outlineLocatesLateAnswerWithoutReadingTheUnrelatedPrefix() {
        String html =
                "<main><h1>Introduction</h1><p>"
                        + "Unrelated background. ".repeat(4000)
                        + "</p><h2>Version 3 retries</h2><p>Retries default to seven attempts.</p></main>";
        WebReadDocument document = document("text/html", html, false);
        String id =
                document.outline().stream()
                        .filter(entry -> entry.section().equals("Version 3 retries"))
                        .map(WebReadDocument.Entry::id)
                        .toList()
                        .getLast();

        assertTrue(document.inspected().isEmpty());
        assertEquals(
                "Retries default to seven attempts.", document.read(List.of(id)).getFirst().text());
        document.recordInspection(List.of(id));
        assertEquals(List.of(id), document.inspected());
        assertEquals("Version 3 retries", document.evidence(id).section());
        assertFalse(document.fullyRead());
    }

    @Test
    void preservesTableCellBoundariesAndCodeWhileRemovingExecutableAndNavigationContent() {
        WebReadDocument document =
                document(
                        "text/html",
                        """
                <html><head><title>Configuration</title><script>stealSecrets()</script></head>
                <body><nav>Navigation noise</nav><main><h2>Timeout</h2>
                <table><tr><th>Setting</th><th>Value</th></tr><tr><td>timeout</td><td>30 seconds</td></tr></table>
                <pre><code>if (ready) {\n    return 30;\n}</code></pre>
                <script>sendCredentials()</script><style>.hidden { display:none }</style>
                <iframe>Injected frame</iframe></main><footer>Footer noise</footer></body></html>
                """,
                        false);
        List<WebReadDocument.Segment> segments =
                document.read(document.outline().stream().map(WebReadDocument.Entry::id).toList());
        document.recordInspection(
                document.outline().stream().map(WebReadDocument.Entry::id).toList());
        String readable =
                String.join("\n", segments.stream().map(WebReadDocument.Segment::text).toList());

        assertTrue(readable.contains("Setting | Value\ntimeout | 30 seconds"));
        assertTrue(readable.contains("if (ready) {\n    return 30;\n}"));
        for (String excluded :
                List.of(
                        "stealSecrets",
                        "sendCredentials",
                        "display:none",
                        "Injected frame",
                        "Navigation noise",
                        "Footer noise")) assertFalse(readable.contains(excluded), excluded);
        assertEquals(3, segments.size());
        assertTrue(document.fullyRead());
    }

    @Test
    void rejectsUnreadOrUnknownEvidenceAndDoesNotPartiallyAuthorizeAnInvalidRead() {
        WebReadDocument document = document("text/plain", "Evidence text", false);
        assertThrows(IllegalArgumentException.class, () -> document.evidence("s1"));
        assertThrows(IllegalArgumentException.class, () -> document.read(List.of("s1", "unknown")));
        assertTrue(document.inspected().isEmpty());

        document.read(List.of("s1"));
        assertTrue(document.inspected().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> document.evidence("s1"));
        document.recordInspection(List.of("s1"));
        assertEquals("Evidence text", document.evidence("s1").quote());
        assertEquals("https://example.com/docs", document.evidence("s1").url());
        assertThrows(IllegalArgumentException.class, () -> document.evidence("unknown"));
    }

    @Test
    void aDifferentDocumentDoesNotInheritReadAuthority() {
        WebReadDocument first = document("text/plain", "First source", false);
        WebReadDocument second = document("text/plain", "Second source", false);
        first.read(List.of("s1"));
        first.recordInspection(List.of("s1"));

        assertThrows(IllegalArgumentException.class, () -> second.evidence("s1"));
        second.read(List.of("s1"));
        second.recordInspection(List.of("s1"));
        assertEquals("Second source", second.evidence("s1").quote());
    }

    @Test
    void capsOversizedSourcesAndNeverClaimsFullCoverageForTruncatedInput() {
        WebReadDocument document = document("text/plain", "x".repeat(12001000), false);
        assertEquals(10000, document.outline().size());
        assertTrue(document.truncated());
        List<String> ids = document.outline().stream().map(WebReadDocument.Entry::id).toList();
        for (int index = 0; index < ids.size(); index += 8) {
            document.read(ids.subList(index, Math.min(index + 8, ids.size())));
            document.recordInspection(ids.subList(index, Math.min(index + 8, ids.size())));
        }
        assertFalse(document.fullyRead());

        WebReadDocument upstreamTruncated = document("text/plain", "Retrieved prefix", true);
        upstreamTruncated.read(List.of("s1"));
        upstreamTruncated.recordInspection(List.of("s1"));
        assertTrue(upstreamTruncated.truncated());
        assertFalse(upstreamTruncated.fullyRead());
    }

    @Test
    void unsupportedAndEmptyPagesAreFailuresRatherThanNegativeFindings() {
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> document("application/pdf", "%PDF", false));
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () -> document("text/html", "<script>onlyScript()</script>", false));
    }

    private static @NonNull WebReadDocument document(
            @NonNull String type, @NonNull String content, boolean truncated) {
        return new WebReadDocument(
                new FetchedPage(
                        URI.create("https://example.com/docs"),
                        200,
                        type,
                        content,
                        truncated,
                        500000));
    }
}
