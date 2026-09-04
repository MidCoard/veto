package top.focess.veto.controller.dto;

public record ProcessVetoRequest(
        String payload, String dagPayloadId, String requestId, String componentSource) {}
