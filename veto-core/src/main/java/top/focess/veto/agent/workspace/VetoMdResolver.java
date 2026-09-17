package top.focess.veto.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves VETO.md ("The Law"): per-root merge of {@code <root>/VETO.md} (primary) + {@code
 * <root>/.veto/VETO.md} (override — appended last, so its rules win), then cross-root concatenation
 * in root order. Read at compile time by the PromptCompiler (config, bypasses tool-call screening).
 * A root with neither contributes nothing.
 */
public record VetoMdResolver(@NonNull List<@NonNull WorkspaceRoot> roots) {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.workspace.VetoMdResolver");

    /** Resolves the concatenated Law across all roots (empty string if none). */
    public @NonNull String resolve() {
        return sources().stream().map(LawSource::content).collect(Collectors.joining("\n\n"));
    }

    /** Source facts in root order, with the local override after its root's primary file. */
    public @NonNull List<@NonNull LawSource> sources() {
        List<@NonNull LawSource> result = new ArrayList<>();
        for (WorkspaceRoot root : roots) {
            appendIfReadable(result, root.hostPath(), "VETO.md", false);
            appendIfReadable(result, root.hostPath(), ".veto/VETO.md", true);
        }
        return List.copyOf(result);
    }

    public record LawSource(
            @NonNull Path root,
            @NonNull String relativePath,
            boolean override,
            @NonNull String content) {}

    private void appendIfReadable(
            @NonNull List<@NonNull LawSource> result,
            @NonNull Path root,
            @NonNull String relativePath,
            boolean override) {
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return;
        }
        try {
            String content = Files.readString(file).strip();
            if (!content.isEmpty()) {
                result.add(new LawSource(root, relativePath, override, content));
            }
        } catch (IOException e) {
            log.warn("Could not read {} — skipping", file, e);
        }
    }
}
