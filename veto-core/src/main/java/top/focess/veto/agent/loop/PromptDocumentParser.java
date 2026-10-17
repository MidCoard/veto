package top.focess.veto.agent.loop;

import static top.focess.veto.agent.loop.PromptDocument.error;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Single-use, thread-confined parser for one trusted MDC source. */
final class PromptDocumentParser {
    private final @NonNull String name;
    private final @NonNull String @NonNull [] lines;
    final @NonNull String declarations;
    private int cursor;
    private int depth;

    PromptDocumentParser(@NonNull String name, @NonNull String source) {
        this.name = name;
        lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 5
                || !lines[0].equals("---")
                || !lines[1].equals("version: 2")
                || !lines[2].matches("id: [A-Za-z][A-Za-z0-9_-]*")
                || !lines[3].matches("requires: \\[.*]")
                || !lines[4].equals("---")) throw error(name, 1, "E_HEADER");
        declarations = lines[3].substring(11, lines[3].length() - 1).strip();
        cursor = 5;
    }

    @NonNull List<PromptDocumentNode> parse(@NonNull String end) {
        if (++depth > 64) throw error(name, cursor + 1, "E_SCOPE_DEPTH");
        List<PromptDocumentNode> nodes = new ArrayList<>();
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
            List<PromptDocumentNode> body = List.of();
            List<PromptDocumentNode> alternative = List.of();
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
            nodes.add(new PromptDocumentNode(name, number, kind, argument, body, alternative));
        }
        if (!end.isEmpty()) throw error(name, lines.length, "E_UNCLOSED_SCOPE");
        depth--;
        return nodes;
    }

    /** Immutable MDC syntax node retaining its original source location. */
    record PromptDocumentNode(
            @NonNull String source,
            int line,
            @NonNull String kind,
            @NonNull String argument,
            @NonNull List<PromptDocumentNode> body,
            @NonNull List<PromptDocumentNode> alternative) {}
}
