package top.focess.veto.agent.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolErrors;

/** Bounded source segments owned by one reader invocation, never a filesystem resource. */
final class WebReadDocument {
    private static final int SEGMENT_CHARS = 1200;
    private static final int MAX_SEGMENTS = 300;
    private static final int MAX_READ_SEGMENTS = 8;
    private static final @NonNull Set<@NonNull String> TEXT_BLOCKS =
            Set.of("h1", "h2", "h3", "h4", "h5", "h6", "p", "pre", "table", "li", "a");
    private final @NonNull List<@NonNull Segment> segments = new ArrayList<>();
    private final @NonNull Set<@NonNull String> inspected = new LinkedHashSet<>();
    private final @NonNull String url;
    private boolean truncated;

    record Segment(@NonNull String id, @NonNull String section, @NonNull String text) {}

    record Entry(@NonNull String id, @NonNull String section) {}

    record Evidence(@NonNull String url, @NonNull String section, @NonNull String quote) {}

    WebReadDocument(@NonNull FetchedPage page) {
        url = page.uri().toString();
        truncated = page.truncated();
        String type = page.contentType();
        if (!(type.startsWith("text/") || type.contains("json") || type.contains("xhtml")))
            ToolErrors.failure(
                    "UNSUPPORTED_CONTENT", "Reader supports HTML and text documents only.");
        if (type.contains("html")) {
            var doc = Jsoup.parse(page.content(), url);
            doc.select("script,style,noscript,iframe,nav,footer,header,form").remove();
            Element main = doc.selectFirst("main,article,[role=main]");
            Element root = main == null ? doc.body() : main;
            String section = doc.title().isBlank() ? "Document" : doc.title();
            var blocks = root.getAllElements();
            for (Element block : blocks) {
                if (Thread.currentThread().isInterrupted())
                    ToolErrors.failure("CANCELLED", "Web reader cancelled.");
                if (block.parents().stream()
                        .anyMatch(p -> p != root && TEXT_BLOCKS.contains(p.tagName()))) continue;
                if (block.tagName().matches("h[1-6]")) section = block.text();
                boolean textBlock = TEXT_BLOCKS.contains(block.tagName());
                Element readable = textBlock ? block.clone() : block;
                if (textBlock) {
                    for (Element link : readable.select("a[href]")) {
                        String destination = link.absUrl("href");
                        if (!destination.isBlank()) link.appendText(" (" + destination + ")");
                    }
                }
                String text =
                        TEXT_BLOCKS.contains(block.tagName())
                                ? readable.wholeText()
                                : readable.ownText();
                if (block.tagName().equals("table")) {
                    StringBuilder rows = new StringBuilder();
                    for (Element row : readable.select("tr"))
                        rows.append(String.join(" | ", row.select("th,td").eachText()))
                                .append('\n');
                    text = rows.toString();
                }
                add(section, text);
            }
        } else add("Document", page.content());
        if (segments.isEmpty())
            ToolErrors.failure("EMPTY_CONTENT", "Page has no readable content.");
    }

    private void add(@NonNull String section, @NonNull String text) {
        String content = text.strip();
        for (int offset = 0; offset < content.length(); offset += SEGMENT_CHARS) {
            if (segments.size() == MAX_SEGMENTS) {
                truncated = true;
                return;
            }
            segments.add(
                    new Segment(
                            "s" + (segments.size() + 1),
                            section.substring(0, Math.min(section.length(), 120)),
                            content.substring(
                                    offset, Math.min(content.length(), offset + SEGMENT_CHARS))));
        }
    }

    @NonNull List<@NonNull Entry> outline() {
        return segments.stream().map(s -> new Entry(s.id(), s.section())).toList();
    }

    @NonNull List<@NonNull Entry> find(@NonNull String query) {
        if (query.isBlank() || query.length() > 200)
            throw new IllegalArgumentException(
                    "Use a non-blank keyword of at most 200 characters.");
        String needle = query.toLowerCase(Locale.ROOT);
        return segments.stream()
                .filter(
                        s ->
                                (s.section() + "\n" + s.text())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(needle))
                .limit(24)
                .map(s -> new Entry(s.id(), s.section()))
                .toList();
    }

    @NonNull List<@NonNull Segment> read(@NonNull List<@NonNull String> ids) {
        if (ids.isEmpty() || ids.size() > MAX_READ_SEGMENTS)
            return ToolErrors.failure(
                    "INVALID_ARGUMENTS", "Read between one and eight segment IDs.");
        return ids.stream().map(this::segment).toList();
    }

    void recordInspection(@NonNull List<@NonNull String> ids) {
        inspected.addAll(ids);
    }

    private @NonNull Segment segment(@NonNull String id) {
        return segments.stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown document segment."));
    }

    @NonNull Evidence evidence(@NonNull String id) {
        if (!inspected.contains(id))
            throw new IllegalArgumentException("Evidence must reference a read segment.");
        var source = segment(id);
        return new Evidence(url, source.section(), source.text());
    }

    boolean fullyRead() {
        return !truncated && inspected.size() == segments.size();
    }

    boolean truncated() {
        return truncated;
    }

    @NonNull List<@NonNull String> inspected() {
        return List.copyOf(inspected);
    }
}
