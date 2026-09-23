package top.focess.veto.agent.loop;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.PromptSpan;

/** Compiles trusted MDC sources. Bound values are data, never executable template source. */
public final class PromptDocument {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();
    private static final @NonNull Pattern VALUE = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final int MAX_OUTPUT = 4_000_000;

    public record Result(
            @NonNull String text,
            @NonNull List<PromptSpan> sources,
            @NonNull List<ChatMessage> messages,
            @NonNull Map<String, String> blocks) {}

    private record Node(
            @NonNull String source,
            int line,
            @NonNull String kind,
            @NonNull String argument,
            @NonNull List<Node> body,
            @NonNull List<Node> alternative) {}

    private static final class Parser {
        private final @NonNull String name;
        private final @NonNull String @NonNull [] lines;
        private int cursor;
        private int depth;

        Parser(@NonNull String name, @NonNull String source) {
            this.name = name;
            lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n");
            if (lines.length < 5
                    || !lines[0].equals("---")
                    || !lines[1].equals("version: 2")
                    || !lines[2].matches("id: [A-Za-z][A-Za-z0-9_-]*")
                    || !lines[3].matches("requires: \\[.*]")
                    || !lines[4].equals("---")) throw error(name, 1, "E_HEADER");
            cursor = 5;
        }

        @NonNull List<Node> parse(@NonNull String end) {
            if (++depth > 64) throw error(name, cursor + 1, "E_SCOPE_DEPTH");
            List<Node> nodes = new ArrayList<>();
            while (cursor < lines.length) {
                int number = cursor + 1;
                String line = lines[cursor++];
                if (!end.isEmpty() && (line.equals(end) || line.equals("@else"))) {
                    cursor--;
                    depth--;
                    return nodes;
                }
                String kind = "text";
                String argument = line;
                List<Node> body = List.of();
                List<Node> alternative = List.of();
                if (line.startsWith("@")) {
                    int space = line.indexOf(' ');
                    kind = space < 0 ? line.substring(1) : line.substring(1, space);
                    argument = space < 0 ? "" : line.substring(space + 1);
                    switch (kind) {
                        case "verbatim" -> {
                            int begin = cursor;
                            while (cursor < lines.length && !lines[cursor].equals("@endverbatim"))
                                cursor++;
                            if (cursor == lines.length)
                                throw error(name, number, "E_UNCLOSED_VERBATIM");
                            String literal =
                                    String.join("\n", Arrays.asList(lines).subList(begin, cursor));
                            cursor++;
                            argument = literal;
                        }
                        case "if", "for", "message", "block" -> {
                            String close = kind.equals("block") ? "@end" : "@end" + kind;
                            body = parse(close);
                            if (kind.equals("if")
                                    && cursor < lines.length
                                    && lines[cursor].equals("@else")) {
                                cursor++;
                                alternative = parse(close);
                            }
                            if (cursor >= lines.length || !lines[cursor++].equals(close))
                                throw error(name, number, "E_UNCLOSED_" + kind);
                        }
                        case "include" -> {
                            if (!argument.matches("[A-Za-z0-9_-]+"))
                                throw error(name, number, "E_INCLUDE");
                        }
                        default -> throw error(name, number, "E_DIRECTIVE");
                    }
                }
                nodes.add(new Node(name, number, kind, argument, body, alternative));
            }
            if (!end.isEmpty()) throw error(name, lines.length, "E_UNCLOSED_SCOPE");
            depth--;
            return nodes;
        }
    }

    private final @NonNull Map<String, String> sources;
    private final @NonNull StringBuilder output = new StringBuilder();
    private final @NonNull List<PromptSpan> spans = new ArrayList<>();
    private final @NonNull List<ChatMessage> messages = new ArrayList<>();
    private final @NonNull Map<String, String> blocks = new TreeMap<>();
    private final @NonNull Set<String> includes = new HashSet<>();
    private boolean inMessage;
    private int steps;

    private PromptDocument(@NonNull Map<String, String> sources) {
        this.sources = sources;
    }

    public static @NonNull Result compile(
            @NonNull String entry,
            @NonNull Map<String, ?> data,
            @NonNull Map<String, String> sources) {
        var compiler = new PromptDocument(sources);
        JsonNode tree = requireNonNull(JSON.valueToTree(data));
        compiler.include(entry, tree);
        return new Result(
                compiler.output.toString(),
                List.copyOf(compiler.spans),
                List.copyOf(compiler.messages),
                Map.copyOf(compiler.blocks));
    }

    private void include(@NonNull String name, @NonNull JsonNode data) {
        String source = sources.get(name);
        if (source == null) throw error(name, 1, "E_INCLUDE");
        if (includes.size() >= 16 || !includes.add(name))
            throw error(name, 1, "E_INCLUDE_CYCLE_OR_DEPTH");
        try {
            Parser parser = new Parser(name, source);
            String declarations =
                    parser.lines[3].substring(11, parser.lines[3].length() - 1).strip();
            ObjectNode scope = JSON.createObjectNode();
            for (String item : declarations.split(",")) {
                String declaration = item.strip();
                if (declaration.isEmpty()) continue;
                if (!declaration.matches("[A-Za-z_][A-Za-z0-9_]*") || scope.has(declaration))
                    throw error(name, 4, "E_DECLARATION");
                scope.set(declaration, lookup(declaration, data, name, 4));
            }
            var nodes = parser.parse("");
            Set<String> names = new HashSet<>();
            scope.fieldNames().forEachRemaining(names::add);
            validate(nodes, names);
            render(nodes, scope);
        } finally {
            includes.remove(name);
        }
    }

    private void validate(@NonNull List<Node> nodes, @NonNull Set<String> names) {
        for (Node node : nodes) {
            String argument = node.argument();
            switch (node.kind()) {
                case "text" -> {
                    var matcher = VALUE.matcher(argument);
                    while (matcher.find()) {
                        String value = matcher.group(1);
                        if (value == null) throw error(node.source(), node.line(), "E_EXPRESSION");
                        String expression = value.strip();
                        variable(
                                expression.startsWith("json(") && expression.endsWith(")")
                                        ? expression.substring(5, expression.length() - 1)
                                        : expression,
                                names,
                                node);
                    }
                    if (VALUE.matcher(argument).replaceAll("").contains("{{")
                            || VALUE.matcher(argument).replaceAll("").contains("}}"))
                        throw error(node.source(), node.line(), "E_EXPRESSION");
                }
                case "if" -> {
                    String path = argument;
                    if (argument.startsWith("contains(") && argument.endsWith(")")) {
                        int comma = argument.indexOf(',');
                        if (comma < 0) throw error(node.source(), node.line(), "E_EXPRESSION");
                        path = argument.substring(9, comma).strip();
                    } else if (argument.startsWith("nonempty(") && argument.endsWith(")"))
                        path = argument.substring(9, argument.length() - 1);
                    else if (argument.startsWith("isjson(") && argument.endsWith(")"))
                        path = argument.substring(7, argument.length() - 1);
                    else if (argument.contains(" == "))
                        path = argument.substring(0, argument.indexOf(" == "));
                    variable(path, names, node);
                    validate(node.body(), names);
                    validate(node.alternative(), names);
                }
                case "for" -> {
                    String[] parts = argument.split(" in ", 2);
                    if (parts.length != 2
                            || parts[0].equals("loop")
                            || !parts[0].matches("[A-Za-z_][A-Za-z0-9_]*"))
                        throw error(node.source(), node.line(), "E_FOR");
                    variable(parts[1], names, node);
                    Set<String> inner = new HashSet<>(names);
                    inner.add(parts[0]);
                    inner.add("loop");
                    validate(node.body(), inner);
                }
                case "include" -> {
                    if (!sources.containsKey(argument))
                        throw error(node.source(), node.line(), "E_INCLUDE");
                }
                case "message" -> {
                    if (!Set.of("system", "user", "assistant").contains(argument))
                        throw error(node.source(), node.line(), "E_MESSAGE");
                    validate(node.body(), names);
                }
                case "block" -> validate(node.body(), names);
                default -> {}
            }
        }
    }

    private static void variable(
            @NonNull String path, @NonNull Set<String> names, @NonNull Node node) {
        if (!path.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw error(node.source(), node.line(), "E_EXPRESSION");
        String root = path.split("\\.")[0];
        if (!names.contains(root)) throw error(node.source(), node.line(), "E_UNDECLARED " + root);
    }

    private void render(@NonNull List<Node> nodes, @NonNull JsonNode data) {
        for (Node node : nodes) {
            if (++steps > 100_000) throw error(node.source(), node.line(), "E_EXPANSION_LIMIT");
            int start = output.length();
            switch (node.kind()) {
                case "verbatim" -> {
                    output.append(node.argument()).append('\n');
                    spans.add(
                            new PromptSpan(
                                    node.source() + ".mdc",
                                    node.line() + 1,
                                    1,
                                    start,
                                    output.length()));
                }
                case "text" -> {
                    var matcher = VALUE.matcher(node.argument());
                    int cursor = 0;
                    while (matcher.find()) {
                        String literal = node.argument().substring(cursor, matcher.start());
                        if (literal.contains("{{") || literal.contains("}}"))
                            throw error(node.source(), node.line(), "E_EXPRESSION");
                        String expression = matcher.group(1);
                        if (expression == null)
                            throw error(node.source(), node.line(), "E_EXPRESSION");
                        output.append(literal).append(interpolate(expression.strip(), data, node));
                        cursor = matcher.end();
                    }
                    String tail = node.argument().substring(cursor);
                    if (tail.contains("{{") || tail.contains("}}"))
                        throw error(node.source(), node.line(), "E_EXPRESSION");
                    output.append(tail).append('\n');
                    spans.add(
                            new PromptSpan(
                                    node.source() + ".mdc",
                                    node.line(),
                                    1,
                                    start,
                                    output.length()));
                }
                case "include" -> include(node.argument(), data);
                case "if" ->
                        render(
                                condition(node.argument(), data, node)
                                        ? node.body()
                                        : node.alternative(),
                                data);
                case "for" -> {
                    String[] parts = node.argument().split(" in ", 2);
                    if (parts.length != 2
                            || parts[0].equals("loop")
                            || !parts[0].matches("[A-Za-z_][A-Za-z0-9_]*"))
                        throw error(node.source(), node.line(), "E_FOR");
                    JsonNode values = lookup(parts[1], data, node.source(), node.line());
                    if (!values.isArray()) throw error(node.source(), node.line(), "E_LIST");
                    int index = 0;
                    for (JsonNode value : values) {
                        if (++steps > 100_000)
                            throw error(node.source(), node.line(), "E_EXPANSION_LIMIT");
                        ObjectNode scope = data.deepCopy();
                        scope.set(parts[0], value);
                        scope.set(
                                "loop",
                                JSON.createObjectNode()
                                        .put("first", index == 0)
                                        .put("last", index == values.size() - 1)
                                        .put("index", index++));
                        render(node.body(), scope);
                    }
                }
                case "message" -> {
                    if (inMessage
                            || !Set.of("system", "user", "assistant").contains(node.argument()))
                        throw error(node.source(), node.line(), "E_MESSAGE");
                    inMessage = true;
                    render(node.body(), data);
                    inMessage = false;
                    String raw = output.substring(start);
                    String content = raw.strip();
                    int from = start + raw.length() - raw.stripLeading().length();
                    int to = from + content.length();
                    var messageSources =
                            spans.stream()
                                    .filter(span -> span.end() > from && span.start() < to)
                                    .map(
                                            span ->
                                                    new PromptSpan(
                                                            span.source(),
                                                            span.line(),
                                                            span.column(),
                                                            Math.max(from, span.start()) - from,
                                                            Math.min(to, span.end()) - from))
                                    .toList();
                    messages.add(
                            new ChatMessage(node.argument(), content, null, null, null, null, null)
                                    .withPromptSources(messageSources));
                }
                case "block" -> {
                    render(node.body(), data);
                    if (node.argument().endsWith(" required") && output.substring(start).isBlank())
                        throw error(node.source(), node.line(), "E_REQUIRED_BLOCK");
                    String key = node.argument().split(" ")[0];
                    if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")
                            || blocks.putIfAbsent(key, output.substring(start).strip()) != null)
                        throw error(node.source(), node.line(), "E_BLOCK");
                }
                default -> throw error(node.source(), node.line(), "E_DIRECTIVE");
            }
            if (output.length() > MAX_OUTPUT)
                throw error(node.source(), node.line(), "E_OUTPUT_LIMIT");
        }
    }

    private static boolean condition(
            @NonNull String expression, @NonNull JsonNode data, @NonNull Node node) {
        if (expression.startsWith("isjson(") && expression.endsWith(")")) {
            JsonNode value =
                    lookup(
                            expression.substring(7, expression.length() - 1),
                            data,
                            node.source(),
                            node.line());
            if (!value.isTextual()) throw error(node.source(), node.line(), "E_TEXT");
            try {
                JsonNode parsed =
                        JSON.reader()
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(value.asText());
                return parsed != null && (parsed.isObject() || parsed.isArray());
            } catch (JsonProcessingException invalid) {
                return false;
            }
        }
        if (expression.startsWith("contains(") && expression.endsWith(")")) {
            int comma = expression.indexOf(',');
            if (comma < 0) throw error(node.source(), node.line(), "E_EXPRESSION");
            JsonNode values =
                    lookup(
                            expression.substring(9, comma).strip(),
                            data,
                            node.source(),
                            node.line());
            try {
                JsonNode sought =
                        requireNonNull(
                                JSON.reader()
                                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .readTree(
                                                expression.substring(
                                                        comma + 1, expression.length() - 1)));
                if (values.isTextual() && sought.isTextual())
                    return values.asText().contains(sought.asText());
                if (!values.isArray()) throw error(node.source(), node.line(), "E_LIST");
                for (JsonNode value : values) if (value.equals(sought)) return true;
                return false;
            } catch (JsonProcessingException failure) {
                throw error(node.source(), node.line(), "E_EXPRESSION");
            }
        }
        if (expression.startsWith("nonempty(") && expression.endsWith(")")) {
            JsonNode value =
                    lookup(
                            expression.substring(9, expression.length() - 1),
                            data,
                            node.source(),
                            node.line());
            if (value.isTextual()) return !value.asText().isBlank();
            if (value.isArray() || value.isObject()) return !value.isEmpty();
            throw error(node.source(), node.line(), "E_COLLECTION");
        }
        int equality = expression.indexOf(" == ");
        if (equality >= 0) {
            JsonNode left =
                    lookup(expression.substring(0, equality), data, node.source(), node.line());
            try {
                JsonNode right =
                        requireNonNull(
                                JSON.reader()
                                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .readTree(expression.substring(equality + 4)));
                if (!right.isValueNode() || left.getNodeType() != right.getNodeType())
                    throw error(node.source(), node.line(), "E_COMPARISON_TYPE");
                return left.isNumber()
                        ? left.decimalValue().compareTo(right.decimalValue()) == 0
                        : left.equals(right);
            } catch (JsonProcessingException failure) {
                throw error(node.source(), node.line(), "E_EXPRESSION");
            }
        }
        JsonNode value = lookup(expression, data, node.source(), node.line());
        if (!value.isBoolean()) throw error(node.source(), node.line(), "E_BOOLEAN");
        return value.booleanValue();
    }

    private static @NonNull String interpolate(
            @NonNull String expression, @NonNull JsonNode data, @NonNull Node node) {
        if (expression.startsWith("json(") && expression.endsWith(")")) {
            JsonNode value =
                    lookup(
                            expression.substring(5, expression.length() - 1),
                            data,
                            node.source(),
                            node.line());
            return canonical(value).toString();
        }
        JsonNode value = lookup(expression, data, node.source(), node.line());
        if (!value.isValueNode() || value.isNull())
            throw error(node.source(), node.line(), "E_SCALAR");
        return value.asText();
    }

    private static @NonNull JsonNode lookup(
            @NonNull String expression, @NonNull JsonNode data, @NonNull String source, int line) {
        if (!expression.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw error(source, line, "E_EXPRESSION");
        JsonNode value = data;
        for (String key : expression.split("\\.")) {
            value = requireNonNull(value.path(key));
            if (value.isMissingNode()) throw error(source, line, "E_VARIABLE " + expression);
        }
        return value;
    }

    /** Object order is deterministic; list order remains semantically meaningful. */
    private static @NonNull JsonNode canonical(@NonNull JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            value.properties()
                    .forEach(field -> fields.put(field.getKey(), canonical(field.getValue())));
            ObjectNode result = JSON.createObjectNode();
            fields.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private static @NonNull IllegalArgumentException error(
            @NonNull String source, int line, @NonNull String code) {
        return new IllegalArgumentException(source + ".mdc:" + line + ":1 " + code);
    }
}
