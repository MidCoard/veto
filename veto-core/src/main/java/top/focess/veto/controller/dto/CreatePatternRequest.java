package top.focess.veto.controller.dto;

/** Request payload for creating an agent pattern with a name and model tier. */
public record CreatePatternRequest(String name, String tier) {}
