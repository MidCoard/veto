package top.focess.veto.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.controller.dto.*;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.plugin.runtime.ScriptPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Installed package catalog; selection belongs to session creation. Never exposes paths, script
 * content or invocation payloads.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final @NonNull PluginManager plugins;
    private final @NonNull RequestAuthorization authorization;

    /** Creates the controller with the plugin manager and request authorizer. */
    public PluginController(
            @NonNull PluginManager plugins, @NonNull RequestAuthorization authorization) {
        this.plugins = plugins;
        this.authorization = authorization;
    }

    /** Saves the desired disabled state for the next backend start. */
    @PostMapping("/{id}/disable")
    public void disable(@PathVariable @NonNull String id) {
        authorization.requireAdmin();
        try {
            plugins.setEnabledOnNextStart(id, false);
        } catch (IllegalArgumentException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin is not installed");
        } catch (DataAccessException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Plugin state could not be saved");
        }
    }

    /** Saves the desired enabled state for the next backend start. */
    @PostMapping("/{id}/enable")
    public void enable(@PathVariable @NonNull String id) {
        authorization.requireAdmin();
        try {
            plugins.setEnabledOnNextStart(id, true);
        } catch (IllegalArgumentException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin is not installed");
        } catch (DataAccessException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Plugin state could not be saved");
        }
    }

    /** Admin-only catalog of installed plugins with their state and contributed point/tool ids. */
    @GetMapping
    @SuppressWarnings(
            "resource") // WHY: PluginLifecycle handles are owned by PluginManager, closed elsewhere
    public @NonNull List<PluginResponse> list() {
        authorization.requireAdmin();
        List<PluginResponse> active =
                plugins.plugins().stream()
                        .map(
                                plugin -> {
                                    var script =
                                            plugin.implementation() instanceof ScriptPlugin value
                                                    ? value
                                                    : null;
                                    return new PluginResponse(
                                            plugin.identity().id(),
                                            plugin.displayName(),
                                            plugin.identity().version(),
                                            script == null ? null : script.digest(),
                                            plugin.state() == PluginState.ACTIVE
                                                    && (script == null || script.active()),
                                            plugins.registrations().stream()
                                                    .filter(r -> r.plugin() == plugin)
                                                    .flatMap(
                                                            r ->
                                                                    r
                                                                            .contributions()
                                                                            .entries()
                                                                            .stream())
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
                                                                            .equals(
                                                                                    plugin.identity()
                                                                                            .id()))
                                                    .map(plugins::toolName)
                                                    .distinct()
                                                    .sorted()
                                                    .toList(),
                                            plugin.state(),
                                            null,
                                            plugins.desiredEnabled(plugin.identity().id()));
                                })
                        .toList();
        var result = new ArrayList<>(active);
        for (var plugin : plugins.declined())
            result.add(
                    new PluginResponse(
                            plugin.id(),
                            plugin.name(),
                            plugin.version(),
                            null,
                            false,
                            List.of(),
                            List.of(),
                            PluginState.DECLINED,
                            plugin.reason().name(),
                            plugins.desiredEnabled(plugin.id())));
        for (var plugin : plugins.disabled())
            result.add(
                    new PluginResponse(
                            plugin.id(),
                            plugin.name(),
                            plugin.version(),
                            null,
                            false,
                            List.of(),
                            List.of(),
                            PluginState.DISABLED,
                            null,
                            plugins.desiredEnabled(plugin.id())));
        return List.copyOf(result);
    }
}
