package top.focess.veto.builtin.tools;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.plugin.contract.FrontendContribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Builtin presentation ships with the plugin and requires no backend actions. */
public final class ToolsFrontend {
    private ToolsFrontend() {}

    /** Serves the bundled tools script; every frontend action is rejected. */
    public static @NonNull FrontendContribution contribution() {
        try (var stream =
                ToolsFrontend.class
                        .getResourceAsStream("/frontend/tools.js")) {
            if (stream == null) throw new IllegalStateException("Missing tools frontend");
            return new FrontendContribution(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    (scope, action, arguments) -> {
                        throw new IllegalArgumentException("Tools presentation has no actions");
                    });
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load tools frontend", failure);
        }
    }
}
