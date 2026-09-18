package top.focess.veto.controller;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.plugin.runtime.ScriptPlugins;

/**
 * Read-only administrator diagnostics. Never exposes paths, script content or invocation payloads.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final @NonNull ScriptPlugins plugins;
    private final @NonNull RequestAuthorization authorization;

    public PluginController(
            @NonNull ScriptPlugins plugins, @NonNull RequestAuthorization authorization) {
        this.plugins = plugins;
        this.authorization = authorization;
    }

    @GetMapping
    public @NonNull List<Map<String, Object>> list() {
        authorization.requireAdmin();
        return plugins.plugins().stream()
                .map(
                        plugin ->
                                Map.<String, Object>of(
                                        "id",
                                        plugin.id(),
                                        "version",
                                        plugin.version(),
                                        "sha256",
                                        plugin.digest(),
                                        "active",
                                        plugin.active(),
                                        "tools",
                                        plugin.tools().stream()
                                                .map(
                                                        tool ->
                                                                "plugin_"
                                                                        + plugin.id()
                                                                        + "__"
                                                                        + tool.id())
                                                .toList()))
                .toList();
    }
}
