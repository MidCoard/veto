package top.focess.veto.controller;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.controller.dto.*;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.plugin.runtime.ScriptPlugin;

/**
 * Installed package catalog; selection belongs to session creation. Never exposes paths, script
 * content or invocation payloads.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final @NonNull PluginManager plugins;
    private final @NonNull RequestAuthorization authorization;

    public PluginController(
            @NonNull PluginManager plugins, @NonNull RequestAuthorization authorization) {
        this.plugins = plugins;
        this.authorization = authorization;
    }

    @GetMapping
    public @NonNull List<PluginResponse> list() {
        authorization.requireAdmin();
        return plugins.plugins().stream()
                .map(
                        plugin -> {
                            var script =
                                    plugin.implementation() instanceof ScriptPlugin value
                                            ? value
                                            : null;
                            return new PluginResponse(
                                    plugin.identity().id(),
                                    plugin.identity().version(),
                                    script == null ? null : script.digest(),
                                    plugin.state() == PluginState.ACTIVE
                                            && (script == null || script.active()),
                                    plugins.registrations().stream()
                                            .filter(r -> r.plugin() == plugin)
                                            .flatMap(r -> r.contributions().entries().stream())
                                            .map(e -> e.point().id().value())
                                            .filter(id -> !id.equals("veto:tools"))
                                            .distinct()
                                            .sorted()
                                            .toList(),
                                    plugins
                                            .catalog()
                                            .entries(StandardContributionPoints.TOOLS)
                                            .stream()
                                            .filter(
                                                    entry ->
                                                            entry.source()
                                                                    .namespace()
                                                                    .equals(plugin.identity().id()))
                                            .map(plugins::toolName)
                                            .toList(),
                                    plugin.state());
                        })
                .toList();
    }
}
