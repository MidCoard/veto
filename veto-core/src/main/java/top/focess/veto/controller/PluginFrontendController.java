package top.focess.veto.controller;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.integration.plugins.storage.PluginInvocationScope;
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
    private final @NonNull SessionAgentRegistry agents;

    /** Creates the controller with its authorization, session, plugin, and agent collaborators. */
    public PluginFrontendController(
            @NonNull RequestAuthorization authorization,
            @NonNull SessionRepository sessions,
            @NonNull SessionPlugins selected,
            @NonNull PluginManager plugins,
            @NonNull SessionAgentRegistry agents) {
        this.authorization = authorization;
        this.sessions = sessions;
        this.selected = selected;
        this.plugins = plugins;
        this.agents = agents;
    }

    /**
     * A frontend module contributed by a plugin bound to the session, with its tool display names.
     */
    public record Module(
            @NonNull String id,
            @NonNull String pluginId,
            int apiVersion,
            @NonNull String source,
            @NonNull Map<String, String> tools) {}

    /** Invocation of a frontend module action on behalf of one of the session's agents. */
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

    /** Lists the frontend modules contributed by the session's active bound plugins. */
    @GetMapping
    public @NonNull ResponseEntity<List<Module>> list(@PathVariable @NonNull String name) {
        var ids = ids(session(name));
        var modules =
                plugins.catalog().entries(StandardContributionPoints.FRONTEND).stream()
                        .filter(
                                e ->
                                        ids.contains(e.source().namespace())
                                                && plugins.plugin(e.source().namespace()).state()
                                                        == PluginState.ACTIVE)
                        .map(
                                e ->
                                        new Module(
                                                e.id().value(),
                                                e.source().namespace(),
                                                1,
                                                e.implementation().module(),
                                                toolNames(e.source().namespace())))
                        .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(modules);
    }

    private @NonNull Map<String, String> toolNames(@NonNull String pluginId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (var entry : plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS)) {
            if (pluginId.equals(entry.source().namespace()))
                names.put(entry.id().localId(), plugins.toolName(pluginId, entry.id().value()));
        }
        for (var entry : plugins.catalog().entries(StandardContributionPoints.TOOLS)) {
            if (pluginId.equals(entry.source().namespace()))
                names.put(entry.id().localId(), plugins.toolName(pluginId, entry.id().value()));
        }
        return Map.copyOf(names);
    }

    /**
     * Runs a frontend module action scoped to the session's owner, id, and a known agent. 400 on
     * malformed requests, 404 on unknown session/agent/module, 503 on plugin failure.
     */
    @PostMapping("/actions")
    @SuppressWarnings(
            "ConstantValue") // WHY: Jackson may deserialize missing fields as null despite @NonNull
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
        if (agents.records(UUID.fromString(session.getId())).stream()
                .noneMatch(agent -> agent.id().equals(request.agentId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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
                                    () -> {
                                        var invocation =
                                                new PluginInvocationScope(
                                                        session.getOwner(), session.getId());
                                        try {
                                            return entry.implementation()
                                                    .handler()
                                                    .handle(
                                                            new FrontendContribution.Scope(
                                                                    session.getOwner(),
                                                                    session.getId(),
                                                                    request.agentId()),
                                                            request.action(),
                                                            arguments);
                                        } finally {
                                            invocation.close();
                                        }
                                    });
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
