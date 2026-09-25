package top.focess.veto.controller.dto;

/**
 * Request payload submitting outbound data, with optional tracing identifiers, to the veto gateway.
 */
public record ProcessVetoRequest(
        String payload, String dagPayloadId, String requestId, String componentSource) {}
