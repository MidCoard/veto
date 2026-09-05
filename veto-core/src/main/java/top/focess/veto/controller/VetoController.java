package top.focess.veto.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.controller.dto.CheckVetoRequest;
import top.focess.veto.controller.dto.ProcessVetoRequest;
import top.focess.veto.i18n.Msg;
import top.focess.veto.veto.VetoGateway;

/**
 * REST controller for gateway Veto Gateway operations. Provides endpoints to process payloads
 * through the veto gate and query gateway status.
 */
@RestController
@RequestMapping("/api/veto")
public class VetoController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.controller.VetoController");

    private final @NonNull VetoGateway vetoGateway;

    public VetoController(@NonNull VetoGateway vetoGateway) {
        this.vetoGateway = vetoGateway;
    }

    /**
     * POST /api/veto/process - Submit a payload through the Veto Gateway for redaction/analysis.
     */
    @PostMapping(
            value = "/process",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> processPayload(
            @RequestBody @NonNull ProcessVetoRequest request) {
        String payload = request.payload();
        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.veto.payloadRequired")));
        }

        String dagPayloadId = request.dagPayloadId();
        if (dagPayloadId == null) {
            dagPayloadId = UUID.randomUUID().toString();
        }
        String requestId = request.requestId();
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        String componentSource = request.componentSource();
        if (componentSource == null) {
            componentSource = "gateway";
        }

        log.info(
                "REST: /api/veto/process - payload={} bytes, source={}",
                payload.length(),
                componentSource);

        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(payload, dagPayloadId, requestId, componentSource);

        return ResponseEntity.ok(
                Map.of(
                        "status", "ok",
                        "decision", result.decision().name(),
                        "processedPayload", result.processedPayload(),
                        "reason", result.reason(),
                        "redactionCount", result.redactionCount(),
                        "allowed", result.isAllowed(),
                        "timestamp", Instant.now().toString()));
    }

    /** GET /api/veto/status - Return gateway statistics. */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(
                Map.of(
                        "status", "ok",
                        "enabled", vetoGateway.isEnabled(),
                        "totalVetoes", vetoGateway.getTotalVetoes(),
                        "totalPasses", vetoGateway.getTotalPasses(),
                        "totalRedactions", vetoGateway.getTotalRedactions(),
                        "timestamp", Instant.now().toString()));
    }

    /** POST /api/veto/check - Simple check: test if a string contains sensitive data. */
    @PostMapping(
            value = "/check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> checkPayload(
            @RequestBody @NonNull CheckVetoRequest request) {
        String payload = request.payload();
        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.veto.payloadRequired")));
        }

        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(
                        payload, "check-" + UUID.randomUUID(), "check", "REST-check");

        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "ok",
                        "safe",
                        result.decision() == VetoGateway.VetoDecision.PASS,
                        "decision",
                        result.decision().name(),
                        "redactionCount",
                        result.redactionCount()));
    }
}
