package top.focess.veto.sandbox;

import top.focess.veto.model.ToolExecutionRequest;

/**
 * C6 Atomic Capability Interface  - defines a single, atomic tool execution capability.
 * Each capability performs exactly one type of OS operation, with strict input validation.
 * No generic command injection (run_bash, exec_raw) is allowed.
 */
public interface AtomicCapability {

    /**
     * @return The unique name of this capability (e.g., "read_safe_file", "compile_cpp_target")
     */
    String getName();

    /**
     * Execute the capability with the given request.
     *
     * @param request The validated tool execution request
     * @return Structured result string (typically JSON)
     * @throws SecurityException if the request violates security constraints
     * @throws IllegalArgumentException if the request arguments are invalid
     */
    String execute(ToolExecutionRequest request) throws SecurityException, IllegalArgumentException;

    /**
     * Validate that the request arguments are safe to execute.
     *
     * @param request The tool execution request
     * @throws SecurityException if validation fails
     */
    void validate(ToolExecutionRequest request) throws SecurityException;
}
