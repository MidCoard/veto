package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PluginBindingsConverterTest {
    @Test
    void readsFormerSecretProtectionIdentityFromDurableSelection() {
        var bindings =
                new PluginBindingsConverter()
                        .convertToEntityAttribute(
                                "[{\"id\":\"org.veto.secret-protection\","
                                        + "\"version\":\"1.0.0\",\"revision\":\"legacy\"}]");
        if (bindings == null) throw new AssertionError("Stored plugin bindings were not read");

        assertEquals("org.veto.secret-protection", bindings.getFirst().id());
        assertEquals("1.0.0", bindings.getFirst().version());
        assertEquals("legacy", bindings.getFirst().revision());
    }
}
