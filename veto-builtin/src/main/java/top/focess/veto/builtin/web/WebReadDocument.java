package top.focess.veto.builtin.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.web.Evidence;
import top.focess.veto.api.web.FetchedPage;

/** Bounded source segments owned by one reader invocation, never a filesystem resource. */
public final class WebReadDocument {
    private static final int SEGMENT_CHARS = 1200;
    private static final int MAX_SEGMENTS = 10000;
    private static final int MAX_READ_SEGMENTS = 8;
    private static final @NonNull Pattern SEARCH_WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final @NonNull Set<@NonNull String> TEXT_BLOCKS =
            Set.of("h1", "h2", "h3", "h4", "h5", "h6", "p", "pre", "table", "li", "a");
    private final @NonNull List<@NonNull Segment> segments = new ArrayList<>();
    private final @NonNull Set<@NonNull String> inspected = new LinkedHashSet<>();
    private final @NonNull String url;
    private boolean truncated;

    record Segment(@NonNull String id, @NonNull String section, @NonNull String text) {}

    record Entry(@NonNull String id, @NonNull String section) {}

    WebReadDocument(@NonNull FetchedPage page) {
        url = page.uri().toString();
        truncated = page.truncated();
        String type = page.contentType();
        if (!(type.startsWith("text/") || type.contains("json") || type.contains("xhtml")))
            ToolErrors.failure(
                    ToolErrorCode.RESULT.UNSUPPORTED_CONTENT,
                    "Unsupported content: the reader supports HTML and text documents only.");
        if (type.contains("html")) {
            var doc = Jsoup.parse(page.content(), url);
            doc.select("script,style,noscript,iframe,nav,footer,header,form").remove();
            Element main = doc.selectFirst("main,article,[role=main]");
            Element root = main == null ? doc.body() : main;
            String section = doc.title().isBlank() ? "Document" : doc.title();
            var blocks = root.getAllElements();
            for (Element block : blocks) {
                if (Thread.currentThread().isInterrupted())
                    ToolErrors.failure(
                            ToolErrorCode.LIFECYCLE.CANCELLED,
                            "Cancelled: the web reader was cancelled.");
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
            ToolErrors.failure(
                    ToolErrorCode.VALIDATION.EMPTY_CONTENT,
                    "Empty content: the page has no readable content.");
    }

    private void add(@NonNull String section, @NonNull String text) {
        String content = text.strip();
        String heading = section.substring(0, Math.min(section.length(), 120));
        for (int offset = 0; offset < content.length(); offset += SEGMENT_CHARS) {
            String piece =
                    content.substring(offset, Math.min(content.length(), offset + SEGMENT_CHARS));
            if (segments.size() == MAX_SEGMENTS) {
                truncated = true;
                return;
            }
            segments.add(new Segment("s" + (segments.size() + 1), heading, piece));
        }
    }

    @NonNull List<@NonNull Entry> outline() {
        return segments.stream().map(s -> new Entry(s.id(), s.section())).toList();
    }

    @NonNull List<@NonNull Entry> find(@NonNull String query) {
        if (query.isBlank() || query.length() > 200)
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: use a non-blank keyword of at most 200 characters.");
        String needle = searchText(query);
        if (needle.isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: use a non-blank keyword of at most 200 characters.");
        return segments.stream()
                .filter(
                        s ->
                                searchText(s.section()).contains(needle)
                                        || searchText(s.text()).contains(needle))
                .sorted(
                        Comparator.comparingInt(
                                s -> searchText(s.section()).contains(needle) ? 0 : 1))
                .limit(24)
                .map(s -> new Entry(s.id(), s.section()))
                .toList();
    }

    private static @NonNull String searchText(@NonNull String text) {
        return SEARCH_WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    @NonNull List<@NonNull Segment> read(@NonNull List<@NonNull String> ids) {
        if (ids.isEmpty() || ids.size() > MAX_READ_SEGMENTS)
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: read between one and eight segment IDs.");
        return ids.stream().map(this::segment).toList();
    }

    void recordInspection(@NonNull List<@NonNull String> ids) {
        inspected.addAll(ids);
    }

    private @NonNull Segment segment(@NonNull String id) {
        var match = segments.stream().filter(s -> s.id().equals(id)).findFirst();
        if (match.isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: the segment id is unknown.");
        return match.get();
    }

    @NonNull Evidence evidence(@NonNull String id) {
        if (!inspected.contains(id))
            ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: evidence must reference a read segment.");
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
