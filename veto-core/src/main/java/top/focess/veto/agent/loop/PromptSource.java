package top.focess.veto.agent.loop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/** Version-one prompt source. Values are data; only registered source files are interpreted. */
public final class PromptSource {
    private static final @NonNull Pattern VARIABLE =
            Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}");
    private static final @NonNull Pattern BLOCK =
            Pattern.compile("@block [A-Za-z][A-Za-z0-9_-]* priority=[0-9]{1,3}( required)?");

    private PromptSource() {}

    public record Span(@NonNull String source, int line, int column, int start, int end) {}

    public record Rendered(@NonNull String id, @NonNull String text, @NonNull List<Span> sources) {}

    private record Condition(boolean parent, int line, int blockLine) {}

    public static @NonNull Rendered compile(
            @NonNull String name,
            @NonNull String source,
            @NonNull Map<String, String> values,
            @NonNull Map<String, Boolean> conditions,
            @NonNull Map<String, String> includes) {
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length < 5 || !lines[0].equals("---") || !lines[1].equals("version: 1"))
            throw error(name, 1, "E_VERSION");
        if (!lines[2].matches("id: [A-Za-z][A-Za-z0-9_-]*")) throw error(name, 3, "E_ID");
        if (!lines[3].matches("requires: \\[[A-Za-z0-9_, ]*]")) throw error(name, 4, "E_REQUIRES");
        Set<String> declared = new HashSet<>();
        String declarations = lines[3].substring(11, lines[3].length() - 1);
        for (String declaration : declarations.split(",")) {
            String key = declaration.strip();
            if (key.isEmpty()) continue;
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || !declared.add(key)
                    || (!values.containsKey(key) && !conditions.containsKey(key)))
                throw error(name, 4, "E_REQUIRED_VARIABLE");
        }
        if (!lines[4].equals("---")) throw error(name, 5, "E_HEADER");
        var output = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        render(
                name,
                lines,
                5,
                values,
                conditions,
                includes,
                declared,
                new ArrayDeque<>(),
                output,
                spans);
        return new Rendered(lines[2].substring(4), output.toString(), List.copyOf(spans));
    }

    private static void render(
            @NonNull String name,
            @NonNull String @NonNull [] lines,
            int first,
            @NonNull Map<String, String> values,
            @NonNull Map<String, Boolean> conditions,
            @NonNull Map<String, String> includes,
            @NonNull Set<String> declared,
            @NonNull Deque<String> stack,
            @NonNull StringBuilder output,
            @NonNull List<Span> spans) {
        if (stack.contains(name) || stack.size() >= 8)
            throw error(name, 1, "E_INCLUDE_CYCLE_OR_DEPTH");
        stack.addLast(name);
        Deque<Condition> branches = new ArrayDeque<>();
        boolean enabled = true;
        int blockLine = -1;
        int blockStart = -1;
        int blockDepth = -1;
        boolean required = false;
        try {
            for (int index = first; index < lines.length; index++) {
                String line = lines[index];
                int number = index + 1;
                if (line.startsWith("@if ")) {
                    String key = line.substring(4);
                    Boolean condition = conditions.get(key);
                    if (!declared.contains(key) || condition == null)
                        throw error(name, number, "E_BOOLEAN");
                    branches.addLast(new Condition(enabled, number, blockLine));
                    enabled = enabled && condition;
                } else if (line.equals("@endif")) {
                    if (branches.isEmpty()) throw error(name, number, "E_UNEXPECTED_ENDIF");
                    Condition branch = branches.removeLast();
                    if (branch.blockLine() != blockLine)
                        throw error(name, number, "E_CROSSED_SCOPE");
                    enabled = branch.parent();
                } else if (line.startsWith("@include ")) {
                    String key = line.substring(9);
                    String included = includes.get(key);
                    if (!key.matches("[A-Za-z][A-Za-z0-9_-]*") || included == null)
                        throw error(name, number, "E_INCLUDE");
                    var nested = new StringBuilder();
                    List<Span> nestedSpans = new ArrayList<>();
                    render(
                            key,
                            included.replace("\r\n", "\n").split("\n", -1),
                            0,
                            values,
                            conditions,
                            includes,
                            declared,
                            stack,
                            nested,
                            nestedSpans);
                    if (enabled) {
                        int offset = output.length();
                        output.append(nested);
                        for (Span span : nestedSpans)
                            spans.add(
                                    new Span(
                                            span.source(),
                                            span.line(),
                                            span.column(),
                                            offset + span.start(),
                                            offset + span.end()));
                    }
                } else if (line.startsWith("@block ")) {
                    if (blockLine >= 0 || !BLOCK.matcher(line).matches())
                        throw error(name, number, "E_BLOCK");
                    blockLine = number;
                    blockStart = output.length();
                    blockDepth = branches.size();
                    required = enabled && line.endsWith(" required");
                } else if (line.equals("@end")) {
                    if (blockLine < 0) throw error(name, number, "E_UNEXPECTED_END");
                    if (branches.size() != blockDepth) throw error(name, number, "E_CROSSED_SCOPE");
                    if (required && output.substring(blockStart).isBlank())
                        throw error(name, blockLine, "E_REQUIRED_BLOCK");
                    blockLine = -1;
                } else {
                    if (line.startsWith("@")) throw error(name, number, "E_DIRECTIVE");
                    var matcher = VARIABLE.matcher(line);
                    var rendered = new StringBuilder();
                    int cursor = 0;
                    while (matcher.find()) {
                        String literal = line.substring(cursor, matcher.start());
                        if (literal.contains("{{") || literal.contains("}}"))
                            throw error(name, number, "E_EXPRESSION");
                        String key = matcher.group(1);
                        if (key == null) throw error(name, number, "E_VARIABLE");
                        String value = values.get(key);
                        if (!declared.contains(key) || value == null)
                            throw error(name, number, "E_VARIABLE");
                        rendered.append(literal).append(value);
                        cursor = matcher.end();
                    }
                    String tail = line.substring(cursor);
                    if (tail.contains("{{") || tail.contains("}}"))
                        throw error(name, number, "E_EXPRESSION");
                    rendered.append(tail);
                    if (enabled) {
                        int start = output.length();
                        output.append(rendered).append('\n');
                        spans.add(new Span(name, number, 1, start, output.length()));
                    }
                }
            }
            if (!branches.isEmpty()) throw error(name, branches.getLast().line(), "E_UNCLOSED_IF");
            if (blockLine >= 0) throw error(name, blockLine, "E_UNCLOSED_BLOCK");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(failure.getMessage() + " [source " + name + "]");
        } finally {
            stack.removeLast();
        }
    }

    private static @NonNull IllegalArgumentException error(
            @NonNull String source, int line, @NonNull String code) {
        return new IllegalArgumentException(source + ":" + line + ":1 " + code);
    }
}
