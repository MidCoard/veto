package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import top.focess.veto.util.Nullness;

class ToolErrorCodeTest {

    @Test
    void parseRoundTripsEveryConstant() {
        for (ToolErrorCode code : Nullness.requireNonNull(ToolErrorCode.values())) {
            assertEquals(code, ToolErrorCode.parse(code.name()));
            assertEquals(code.name(), code.id());
        }
    }

    @Test
    void parseToleratesUnknownAndBlankNames() {
        assertNull(ToolErrorCode.parse(null));
        assertNull(ToolErrorCode.parse(""));
        assertNull(ToolErrorCode.parse("   "));
        assertNull(ToolErrorCode.parse("NO_SUCH_CODE"));
        assertNull(ToolErrorCode.parse("io_error"));
    }
}
