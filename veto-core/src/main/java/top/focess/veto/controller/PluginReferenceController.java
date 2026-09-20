package top.focess.veto.controller;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.extension.contract.*;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/** Authenticated browser extension bridge. Reference values never enter the agent/tool pipeline. */
@RestController
@RequestMapping("/api/sessions/{name}/plugin-references")
public final class PluginReferenceController {
    private final @NonNull RequestAuthorization authorization;
    private final @NonNull SessionRepository sessions;
    private final @NonNull SessionPlugins selected;
    private final @NonNull PluginManager plugins;

    public PluginReferenceController(
            @NonNull RequestAuthorization authorization,
            @NonNull SessionRepository sessions,
            @NonNull SessionPlugins selected,
            @NonNull PluginManager plugins) {
        this.authorization = authorization;
        this.sessions = sessions;
        this.selected = selected;
        this.plugins = plugins;
    }

    public record Renderer(
            @NonNull String id,
            @NonNull String pluginId,
            @NonNull String tokenType,
            @NonNull PluginView initialView) {}

    public record ActionRequest(
            @NonNull String rendererId,
            @NonNull String agentId,
            @NonNull String reference,
            @NonNull String action) {}

    private @NonNull SessionEntity session(@NonNull String name) {
        return sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc(
                        name, authorization.requireUser())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private @NonNull List<String> ids(@NonNull SessionEntity session) {
        return selected.bindings(session.getId()).stream().map(PluginBinding::id).toList();
    }

    @GetMapping
    public @NonNull List<Renderer> list(@PathVariable @NonNull String name) {
        var ids = ids(session(name));
        return plugins.catalog().entries(StandardExtensionPoints.REFERENCE_RENDERERS).stream()
                .filter(e -> ids.contains(e.source().namespace()))
                .map(
                        e ->
                                new Renderer(
                                        e.id().value(),
                                        e.source().namespace(),
                                        e.implementation().tokenType(),
                                        e.implementation().initialView()))
                .toList();
    }

    @PostMapping("/actions")
    public @NonNull ResponseEntity<PluginView> act(
            @PathVariable @NonNull String name, @RequestBody @NonNull ActionRequest request) {
        var session = session(name);
        var ids = ids(session);
        if (request.reference().length() > 256
                || request.agentId().length() > 128
                || !request.action().matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var entry =
                plugins.catalog().entries(StandardExtensionPoints.REFERENCE_RENDERERS).stream()
                        .filter(
                                e ->
                                        e.id().value().equals(request.rendererId())
                                                && ids.contains(e.source().namespace()))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            var value =
                    plugins.plugin(entry.source().namespace())
                            .execute(
                                    () ->
                                            entry.implementation()
                                                    .handler()
                                                    .handle(
                                                            new ReferenceRenderer.Scope(
                                                                    session.getOwner(),
                                                                    session.getId(),
                                                                    request.agentId()),
                                                            request.reference(),
                                                            request.action()));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .body(value.orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE)));
        } catch (ExtensionFailure failure) {
            throw new ResponseStatusException(
                    failure.code() == ExtensionFailure.Code.INVALID_ARGUMENTS
                            ? HttpStatus.BAD_REQUEST
                            : HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
