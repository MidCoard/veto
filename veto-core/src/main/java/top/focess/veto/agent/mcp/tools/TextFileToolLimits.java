package top.focess.veto.agent.mcp.tools;

/** Shared resource limits for native tools that load or write complete UTF-8 text files. */
final class TextFileToolLimits {

    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final String DISPLAY_SIZE = "16 MiB (16,777,216 bytes)";

    private TextFileToolLimits() {}
}
