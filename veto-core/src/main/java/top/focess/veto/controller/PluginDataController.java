package top.focess.veto.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.RetainedPluginData;

/** Authenticated host inventory of retained, uninterpreted plugin data. */
@RestController
@RequestMapping("/api/plugin-data")
public final class PluginDataController {
    private final @NonNull RetainedPluginData data;

    /** Creates the host-owned retained-data controller. */
    public PluginDataController(@NonNull RetainedPluginData data) {
        this.data = data;
    }

    /** Pages metadata only; no raw payload is included in a listing. */
    @GetMapping("/records")
    public @NonNull ResponseEntity<RetainedPluginData.Page> list(
            @RequestParam PluginStorage.@NonNull Kind kind,
            @RequestParam(required = false) String pluginId,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(data.list(kind, pluginId, after, limit));
    }

    /** Deliberately exports one authorized opaque record with its original provenance. */
    @GetMapping("/records/{id}/export")
    public @NonNull ResponseEntity<RetainedPluginData.Export> export(
            @PathVariable @NonNull String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data.export(id));
    }
}
