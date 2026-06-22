package top.focess.veto.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

/** Smoke tests for {@link TuiRenderer} — the module's first render tests. */
class TuiRendererTest {

    @Test
    void renderProducesExactlyHeightLines() {
        TuiState state = new TuiState(new TuiTheme());
        state.handleResize(80, 24);
        TuiRenderer.RenderResult result = new TuiRenderer().render(state);
        assertEquals(24, result.lines().size());
    }

    @Test
    void headerContainsTitle() {
        TuiState state = new TuiState(new TuiTheme());
        state.handleResize(80, 24);
        TuiRenderer.RenderResult result = new TuiRenderer().render(state);
        boolean found = false;
        for (AttributedString line : result.lines()) {
            if (line.toString().contains("VETO TUI")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "rendered output should contain the VETO TUI title");
    }
}
