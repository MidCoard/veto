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
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.builtin.web.model.Evidence;
import top.focess.veto.builtin.web.model.FetchedPage;

/** Bounded source segments owned by one reader invocation, never a filesystem resource. */
public final class WebReadDocument {
    private static final int SEGMENT_CHARS = 1200;
    private static final int MAX_SEGMENTS = 10000;
    private static final @NonNull Pattern SEARCH_WHITESPACE = Pattern.compile("(?U)\\s+");
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
            readHtml(root, doc.title().isBlank() ? "Document" : doc.title());
        } else add("Document", page.content());
        if (segments.isEmpty())
            ToolErrors.failure(
                    ToolErrorCode.VALIDATION.EMPTY_CONTENT,
                    "Empty content: the page has no readable content.");
    }

    /** Collects contiguous inline text, flushing before and after each nested block. */
    private void readHtml(@NonNull Element root, @NonNull String title) {
        NodeTraversor.filter(
                new NodeFilter() {
                    private @NonNull String section = title;
                    private final @NonNull StringBuilder text = new StringBuilder();

                    public @NonNull FilterResult head(@NonNull Node node, int depth) {
                        if (Thread.currentThread().isInterrupted())
                            ToolErrors.failure(
                                    ToolErrorCode.LIFECYCLE.CANCELLED,
                                    "Cancelled: the web reader was cancelled.");
                        if (node instanceof TextNode value) {
                            text.append(value.getWholeText());
                        } else if (node instanceof Element element) {
                            if (element.isBlock()) flush();
                            if (element.tagName().matches("h[1-6]")) section = element.text();
                            if (element.tagName().equals("table")
                                    || element.tagName().equals("pre")) {
                                for (Element link : element.select("a[href]")) {
                                    String destination = link.absUrl("href");
                                    if (!destination.isBlank())
                                        link.appendText(" (" + destination + ")");
                                }
                            }
                            if (element.tagName().equals("table")) {
                                for (Element row : element.select("tr"))
                                    text.append(String.join(" | ", row.select("th,td").eachText()))
                                            .append('\n');
                                return FilterResult.SKIP_CHILDREN;
                            }
                            if (element.tagName().equals("pre")) {
                                text.append(element.wholeText());
                                return FilterResult.SKIP_CHILDREN;
                            }
                            if (element.tagName().equals("br")) text.append('\n');
                        }
                        return FilterResult.CONTINUE;
                    }

                    public @NonNull FilterResult tail(@NonNull Node node, int depth) {
                        if (node instanceof Element element) {
                            if (element.tagName().equals("a")) {
                                String destination = element.absUrl("href");
                                if (!destination.isBlank())
                                    text.append(" (").append(destination).append(')');
                            }
                            if (element.isBlock() || depth == 0) flush();
                        }
                        return FilterResult.CONTINUE;
                    }

                    private void flush() {
                        add(section, text.toString());
                        text.setLength(0);
                    }
                },
                root);
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
        String needle = searchText(query);
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
        return ids.stream().map(this::segment).toList();
    }

    void recordInspection(@NonNull List<@NonNull String> ids) {
        inspected.addAll(ids);
    }

    private @NonNull Segment segment(@NonNull String id) {
        var match = segments.stream().filter(s -> s.id().equals(id)).findFirst();
        return match.orElseGet(
                () ->
                        ToolErrors.failure(
                                ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                                "Invalid arguments: the segment id is unknown."));
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
