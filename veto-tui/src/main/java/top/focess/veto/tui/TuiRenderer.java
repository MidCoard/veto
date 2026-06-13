package top.focess.veto.tui;

import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import top.focess.veto.contract.IpcFrame;

/**
 * Renders the full-screen immediate-mode TUI layout based on the current TuiState. Automatically
 * aligns and pads elements to fit the terminal dimensions precisely.
 */
public final class TuiRenderer {

    public record RenderResult(List<AttributedString> lines, int cursorOffset) {}

    public RenderResult render(TuiState state) {
        int width = Math.max(20, state.getTerminalWidth());
        int height = Math.max(10, state.getTerminalHeight());
        int renderWidth = width - 1;

        List<AttributedString> lines = new ArrayList<>(height);

        // 1. Line 0: Header Panel
        lines.add(renderHeader(state, renderWidth));

        // 2. Line 1: Top Divider
        lines.add(renderHorizontalBorder(renderWidth, "┌", "─", "┐"));

        // 3. Lines 2 to H+1 (Output Logs)
        int outputHeight = Math.max(1, height - 7);
        List<AttributedString> wrappedLogs = state.getWrappedLogs();
        int wrappedSize = wrappedLogs.size();
        int scrollOffset = state.getScrollOffset();

        int startIdx = 0;
        int endIdx = wrappedSize;

        if (wrappedSize > outputHeight) {
            int maxScroll = wrappedSize - outputHeight;
            int clampedScroll = Math.max(0, Math.min(scrollOffset, maxScroll));
            startIdx = wrappedSize - outputHeight - clampedScroll;
            endIdx = startIdx + outputHeight;
        }

        for (int i = startIdx; i < endIdx; i++) {
            lines.add(renderLogLine(wrappedLogs.get(i), renderWidth));
        }

        // Fill remaining viewport space with empty lines if log size is smaller than OutputHeight
        int actualLogCount = endIdx - startIdx;
        for (int i = actualLogCount; i < outputHeight; i++) {
            lines.add(renderLogLine(AttributedString.EMPTY, renderWidth));
        }

        // 4. Line H+2: Scroll Status or Autocomplete Suggestions
        if (!state.getAutocompleteCandidates().isEmpty()) {
            lines.add(renderSuggestions(state, renderWidth));
        } else {
            lines.add(
                    renderScrollStatus(
                            state, renderWidth, wrappedSize, scrollOffset, outputHeight));
        }

        // 5. Line H+3: Divider between Scroll Status and Input
        lines.add(renderHorizontalBorder(renderWidth, "├", "─", "┤"));

        // 6. Line H+4: Input Panel
        int inputLineIndex = height - 3;
        RenderInputResult inputResult = renderInputLine(state, renderWidth);
        lines.add(inputResult.line());

        // 7. Line H+5: Divider between Input and Footer
        lines.add(renderHorizontalBorder(renderWidth, "└", "─", "┘"));

        // 8. Line H+6: Shortcut Footer
        lines.add(renderFooter(renderWidth));

        // Ensure we returned exactly `height` lines
        while (lines.size() < height) {
            lines.add(AttributedString.EMPTY);
        }
        if (lines.size() > height) {
            lines = lines.subList(0, height);
        }

        // Calculate cursor offset
        int cursorOffset = inputLineIndex * width + inputResult.cursorCol();

        return new RenderResult(lines, cursorOffset);
    }

    private AttributedString renderHeader(TuiState state, int width) {
        AttributedStringBuilder header = new AttributedStringBuilder();
        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        header.append(" ");
        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.CYAN)
                        .bold());
        header.append("■ VETO TUI ■");
        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        header.append("   Connection: ");

        if (state.getConnection() == TuiState.ConnectionState.CONNECTED) {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.GREEN)
                            .bold());
            header.append("Connected");
        } else if (state.getConnection() == TuiState.ConnectionState.CONNECTING) {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.YELLOW)
                            .bold());
            header.append("Connecting");
        } else {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.RED)
                            .bold());
            header.append("Disconnected");
        }

        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        header.append("  │  User: ");
        if (state.getUsername() != null) {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.GREEN));
            header.append(state.getUsername());
        } else {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.WHITE + 8));
            header.append("None");
        }

        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        header.append("  │  Turns: ");
        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.YELLOW));
        header.append(String.valueOf(state.getTurnCount()));

        header.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        header.append("  │  ID: ");
        if (state.getSessionId() != null) {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.CYAN));
            header.append(state.getSessionId());
        } else {
            header.style(
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.WHITE + 8));
            header.append("N/A");
        }

        int currentLen = header.length();
        int pad = width - currentLen;
        if (pad > 0) {
            header.style(AttributedStyle.DEFAULT.background(AttributedStyle.WHITE + 8));
            header.append(" ".repeat(pad));
        } else if (pad < 0) {
            return new AttributedString(
                    header.subSequence(0, width).toString(),
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.WHITE));
        }
        return header.toAttributedString();
    }

    private AttributedString renderHorizontalBorder(
            int width, String left, String middle, String right) {
        int midWidth = Math.max(0, width - 2);
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append(left);
        sb.append(middle.repeat(midWidth));
        sb.append(right);
        return sb.toAttributedString();
    }

    private AttributedString renderSuggestions(TuiState state, int width) {
        List<String> candidates = state.getAutocompleteCandidates();
        int selected = state.getSelectedAutocompleteIndex();

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append("├─ Suggestions: ");

        int windowSize = 5;
        int start = Math.max(0, selected - windowSize / 2);
        int end = Math.min(candidates.size(), start + windowSize);
        if (end - start < windowSize && start > 0) {
            start = Math.max(0, end - windowSize);
        }

        if (start > 0) {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
            sb.append("... ");
        }

        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
                sb.append("  ");
            }
            String cand = candidates.get(i);
            if (i == selected) {
                sb.style(
                        AttributedStyle.DEFAULT
                                .foreground(AttributedStyle.GREEN)
                                .bold()
                                .underline());
                sb.append(cand);
            } else {
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
                sb.append(cand);
            }
        }

        if (end < candidates.size()) {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
            sb.append(" ...");
        }

        int currentLen = sb.length();
        int pad = width - currentLen - 1;
        if (pad > 0) {
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
            sb.append("─".repeat(pad));
        }
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append("┤");

        return sb.toAttributedString();
    }

    private AttributedString renderLogLine(AttributedString content, int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append("│ ");
        sb.append(content);

        int contentLen = content.length();
        int padWidth = width - 4; // content has margins of 2 spaces on each side
        int pad = padWidth - contentLen;
        if (pad > 0) {
            sb.style(AttributedStyle.DEFAULT);
            sb.append(" ".repeat(pad));
        }
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append(" │");
        return sb.toAttributedString();
    }

    private AttributedString renderScrollStatus(
            TuiState state, int width, int totalLines, int scrollOffset, int outputHeight) {
        int maxScroll = Math.max(0, totalLines - outputHeight);
        String pctStr;
        if (maxScroll == 0 || scrollOffset <= 0) {
            pctStr = "BOT";
        } else if (scrollOffset >= maxScroll) {
            pctStr = "TOP";
        } else {
            int pct = (int) Math.round((1 - (double) scrollOffset / maxScroll) * 100);
            pctStr = pct + "%";
        }

        int viewedBottom = Math.max(0, totalLines - scrollOffset);

        String leftText = "─ [PAGE UP/DN] Scroll (Line: " + viewedBottom + "/" + totalLines + ") ";
        String rightText = " [" + pctStr + "] ─";

        int midPad = width - leftText.length() - rightText.length() - 2; // -2 for ├ and ┤
        String midLine = "─".repeat(Math.max(0, midPad));

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append("├");
        sb.append(leftText);
        sb.append(midLine);
        sb.append(rightText);
        sb.append("┤");
        return sb.toAttributedString();
    }

    private record RenderInputResult(AttributedString line, int cursorCol) {}

    private RenderInputResult renderInputLine(TuiState state, int width) {
        AttributedStringBuilder prefix = new AttributedStringBuilder();
        IpcFrame.Prompt activePrompt = state.getActivePrompt();

        if (activePrompt != null) {
            prefix.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
            prefix.append("[");
            prefix.append(activePrompt.content());
            prefix.append("] ▸ ");
        } else {
            prefix.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
            prefix.append("[veto] ");
            if (state.getUsername() != null) {
                prefix.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold());
                prefix.append("▸ ");
            } else {
                prefix.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold());
                prefix.append("◇ ");
            }
        }

        int prefixLen = prefix.length();
        int maxInputWidth = Math.max(10, width - 4 - prefixLen);

        String rawInput = state.getCommandBuffer().toString();
        // Mask input if active prompt has mask metadata
        boolean mask = activePrompt != null && Boolean.TRUE.equals(activePrompt.meta().get("mask"));
        String inputToRender = mask ? "*".repeat(rawInput.length()) : rawInput;

        int cursorIndex = state.getCursorIndex();

        // Horizontal input scrolling
        String displayedInput = inputToRender;
        int displayedCursor = cursorIndex;

        if (inputToRender.length() > maxInputWidth) {
            int start = Math.max(0, cursorIndex - maxInputWidth + 5);
            if (start + maxInputWidth > inputToRender.length()) {
                start = inputToRender.length() - maxInputWidth;
            }
            displayedInput = inputToRender.substring(start, start + maxInputWidth);
            displayedCursor = cursorIndex - start;
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append("│ ");
        sb.append(prefix);
        sb.style(AttributedStyle.DEFAULT); // normal input text style
        sb.append(displayedInput);

        int totalWritten = prefixLen + displayedInput.length();
        int pad = (width - 4) - totalWritten;
        if (pad > 0) {
            sb.style(AttributedStyle.DEFAULT);
            sb.append(" ".repeat(pad));
        }

        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE + 8));
        sb.append(" │");

        // Cursor column on the line is: 2 spaces border + prefix length + displayed cursor offset
        int cursorCol = 2 + prefixLen + displayedCursor;

        return new RenderInputResult(sb.toAttributedString(), cursorCol);
    }

    private AttributedString renderFooter(int width) {
        AttributedStringBuilder footer = new AttributedStringBuilder();
        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        footer.append(" ");

        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE)
                        .bold());
        footer.append("Ctrl+C:");
        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        footer.append(" Quit/Cancel  │  ");

        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE)
                        .bold());
        footer.append("PgUp/PgDn:");
        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        footer.append(" Scroll Logs  │  ");

        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE)
                        .bold());
        footer.append("Tab:");
        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        footer.append(" Complete  │  ");

        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE)
                        .bold());
        footer.append("Esc:");
        footer.style(
                AttributedStyle.DEFAULT
                        .background(AttributedStyle.WHITE + 8)
                        .foreground(AttributedStyle.WHITE));
        footer.append(" Clear");

        int currentLen = footer.length();
        int pad = width - currentLen;
        if (pad > 0) {
            footer.style(AttributedStyle.DEFAULT.background(AttributedStyle.WHITE + 8));
            footer.append(" ".repeat(pad));
        } else if (pad < 0) {
            return new AttributedString(
                    footer.subSequence(0, width).toString(),
                    AttributedStyle.DEFAULT
                            .background(AttributedStyle.WHITE + 8)
                            .foreground(AttributedStyle.WHITE));
        }

        return footer.toAttributedString();
    }
}
