package top.focess.veto.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import top.focess.veto.extension.contract.*;

class PluginViewTest {
    @Test
    void ordinaryStatusPluginUsesSameProtocolWithoutCredentialDependencies() throws Exception {
        var initial =
                new PluginView(
                        List.of(
                                new PluginView.Button(
                                        PluginView.Label.literal("Refresh status"),
                                        new PluginView.Action(
                                                PluginView.ActionTarget.SERVER, "refresh"))),
                        0,
                        false);
        var renderer =
                new ReferenceRenderer(
                        "JOB_REF",
                        initial,
                        (scope, reference, action) -> {
                            if (!action.equals("refresh"))
                                throw new ExtensionFailure(ExtensionFailure.Code.INVALID_ARGUMENTS);
                            var group =
                                    new PluginView.Group(
                                            PluginView.Direction.COLUMN,
                                            List.of(
                                                    new PluginView.Text(
                                                            PluginView.Label.literal(reference)),
                                                    new PluginView.Text(
                                                            PluginView.Label.literal(
                                                                    "Completed"))));
                            return Optional.of(new PluginView(List.of(group), 0, false));
                        });
        var view =
                renderer.handler()
                        .handle(
                                new ReferenceRenderer.Scope("owner", "session", "agent"),
                                "build-42",
                                "refresh")
                        .orElseThrow();
        var group = (PluginView.Group) view.content().getFirst();
        assertEquals("Completed", ((PluginView.Text) group.children().get(1)).text().fallback());
        assertEquals(0, view.resetAfterMillis());
        assertFalse(view.resetOnHidden());
        assertThrows(
                ExtensionFailure.class,
                () ->
                        renderer.handler()
                                .handle(
                                        new ReferenceRenderer.Scope("o", "s", "a"),
                                        "build-42",
                                        "unknown"));
    }

    @Test
    void rejectsUnboundedUiTrees() {
        var text = new PluginView.Text(PluginView.Label.literal("text"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginView(Collections.nCopies(129, text), 0, false));
        assertThrows(
                IllegalArgumentException.class, () -> new PluginView(List.of(text), -1, false));
    }
}
