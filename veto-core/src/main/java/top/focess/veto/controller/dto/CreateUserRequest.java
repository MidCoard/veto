package top.focess.veto.controller.dto;

/** Request payload for registering a new user with a role. */
public record CreateUserRequest(String username, String password, String role) {}
