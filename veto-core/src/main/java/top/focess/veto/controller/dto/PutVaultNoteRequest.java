package top.focess.veto.controller.dto;

/** Request payload for writing a titled note into the user's vault. */
public record PutVaultNoteRequest(String title, String value) {}
