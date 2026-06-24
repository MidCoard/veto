package top.focess.veto.agent.mcp.tools;

import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code grep_search} — search for exact pattern matches inside files. */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY)
public final class GrepSearchTool implements NativeMcpTool<GrepSearchTool.Args> {

    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to search under.")
                    String searchPath,
            @Doc("The exact pattern to match.") String query,
            @Nullable @Doc("Whether to match case-insensitively.") Boolean caseInsensitive,
            @Nullable @Doc("Glob filters for which files to include.") List<String> includes) {}

    @Override
    public String getName() {
        return "grep_search";
    }

    @Override
    public String getDescription() {
        return "Search for exact pattern matches inside files.";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public String execute(Args args) throws IOException {
        Path root = Path.of(args.searchPath());
        if (!Files.exists(root)) {
            return "{\"status\":\"error\",\"error\":\"Path not found: " + args.searchPath() + "\"}";
        }
        boolean ci = Boolean.TRUE.equals(args.caseInsensitive());
        String query = ci ? args.query().toLowerCase() : args.query();
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try (Stream<String> lines =
                                        Files.lines(file, StandardCharsets.UTF_8)) {
                                    List<String> all = lines.toList();
                                    for (int i = 0; i < all.size(); i++) {
                                        String line = all.get(i);
                                        String candidate = ci ? line.toLowerCase() : line;
                                        if (candidate.contains(query)) {
                                            sb.append(file)
                                                    .append(':')
                                                    .append(i + 1)
                                                    .append(": ")
                                                    .append(line)
                                                    .append('\n');
                                        }
                                    }
                                } catch (IOException ignored) {
                                }
                            });
        }
        return sb.toString();
    }
}
