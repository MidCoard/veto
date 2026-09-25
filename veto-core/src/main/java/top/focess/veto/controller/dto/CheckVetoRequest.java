package top.focess.veto.controller.dto;

/** Request payload asking whether a string contains sensitive data. */
public record CheckVetoRequest(String payload) {}
