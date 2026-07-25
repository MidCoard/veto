package top.focess.veto.agent.loop;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Substitutes {@code {{KEY}}} markers in the system-prompt template with rendered blocks, producing
 * the final compiled system prompt. This is the "compile / linking" step: the markdown template
 * owns the static skeleton and marker placement; the {@code PromptCompiler} supplies the dynamic
 * blocks (law, identity, role, tools, boundaries, skills, per-turn reminder).
 *
 * <p><b>Only known markers</b> (the keys in the supplied map) are replaced. Any other {@code
 * {{...}}} sequence in the template passes through verbatim, so prompt content that legitimately
 * contains double braces (e.g. JSON examples) is never mangled. When a marker's rendered value is
 * blank, the substitution leaves an empty line; a final pass collapses runs of 3+ newlines to 2 so
 * optional sections (skills, boundaries under FULL_ACCESS) leave no orphan headers.
 */
public final class PromptTemplate {

    private PromptTemplate() {}

    /**
     * Substitutes the known markers, then collapses excess blank lines.
     *
     * @param template the raw template text (loaded from {@code default-system-prompt.md})
     * @param blocks marker key -> rendered block (blank/empty = section omitted)
     */
    public static @NonNull String render(
            @NonNull String template, @NonNull Map<String, String> blocks) {
        String out = template.replace("\r\n", "\n").replace('\r', '\n');
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{{" + e.getKey() + "}}", value);
        }
        out = out.replaceAll("\n{3,}", "\n\n");
        return out.strip();
    }
}
