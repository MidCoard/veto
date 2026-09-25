package top.focess.veto.training;

/**
 * Request body for the deploy endpoint. An empty {@code modelPath} deploys the most recently
 * trained model, falling back to the configured default GGUF.
 */
public record DeployModelRequest(String modelPath) {}
