package top.focess.veto.controller.dto;

/** Request payload carrying username and password for login or initial setup. */
public record AuthCredentials(String username, String password) {}
