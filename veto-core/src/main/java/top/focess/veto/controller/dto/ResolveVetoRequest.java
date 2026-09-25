package top.focess.veto.controller.dto;

/** Request payload carrying the chosen option for resolving a pending veto. */
public record ResolveVetoRequest(String option) {}
