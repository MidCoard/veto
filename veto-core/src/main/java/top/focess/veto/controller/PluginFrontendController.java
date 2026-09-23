package top.focess.veto.controller;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/** Session-authorized frontend code and actions; separate from model tools and history. */
@RestController
@RequestMapping("/api/sessions/{name}/plugin-frontend")
public final class PluginFrontendController {
    private final @NonNull RequestAuthorization authorization;
    private final @NonNull SessionRepository sessions;
    private final @NonNull SessionPlugins selected;
    private final @NonNull PluginManager plugins;

    public PluginFrontendController(
            @NonNull RequestAuthorization authorization,
            @NonNull SessionRepository sessions,
            @NonNull SessionPlugins selected,
            @NonNull PluginManager plugins) {
        this.authorization = authorization;
        this.sessions = sessions;
        this.selected = selected;
        this.plugins = plugins;
    }

    public record Module(
            @NonNull String id, @NonNull String pluginId, int apiVersion, @NonNull String source) {}

    public record ActionRequest(
            @NonNull String moduleId,
            @NonNull String agentId,
            @NonNull String action,
            @NonNull JsonNode arguments) {}

    private @NonNull SessionEntity session(@NonNull String name) {
        return sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc(
                        name, authorization.requireUser())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private @NonNull List<String> ids(@NonNull SessionEntity session) {
        return selected.bindings(session.getId()).stream().map(PluginBinding::id).toList();
    }

    @GetMapping
    public @NonNull ResponseEntity<List<Module>> list(@PathVariable @NonNull String name) {
        var ids = ids(session(name));
        var modules =
                plugins.catalog().entries(StandardContributionPoints.FRONTEND).stream()
                        .filter(e -> ids.contains(e.source().namespace()))
                        .map(
                                e ->
                                        new Module(
                                                e.id().value(),
                                                e.source().namespace(),
                                                1,
                                                e.implementation().module()))
                        .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(modules);
    }

    @PostMapping("/actions")
    public @NonNull ResponseEntity<JsonNode> act(
            @PathVariable @NonNull String name, @RequestBody @NonNull ActionRequest request) {
        var session = session(name);
        var ids = ids(session);
        if (request.moduleId() == null
                || request.moduleId().length() > 256
                || request.agentId() == null
                || !request.agentId().matches("[a-zA-Z0-9_-]{1,128}")
                || request.action() == null
                || !request.action().matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")
                || request.arguments() == null
                || !request.arguments().isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var entry =
                plugins.catalog().entries(StandardContributionPoints.FRONTEND).stream()
                        .filter(
                                e ->
                                        ids.contains(e.source().namespace())
                                                && e.id().value().equals(request.moduleId()))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        final JsonValue.ObjectValue arguments;
        try {
            arguments = PluginJson.object(request.arguments());
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            var value =
                    plugins.plugin(entry.source().namespace())
                            .execute(
                                    () ->
                                            entry.implementation()
                                                    .handler()
                                                    .handle(
                                                            new FrontendContribution.Scope(
                                                                    session.getOwner(),
                                                                    session.getId(),
                                                                    request.agentId()),
                                                            request.action(),
                                                            arguments));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .body(PluginJson.toNode(value));
        } catch (PluginFailure failure) {
            throw new ResponseStatusException(
                    failure.code() == PluginFailure.Code.INVALID_ARGUMENTS
                            ? HttpStatus.BAD_REQUEST
                            : HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
