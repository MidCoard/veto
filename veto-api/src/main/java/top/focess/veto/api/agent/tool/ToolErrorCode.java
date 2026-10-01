package top.focess.veto.api.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Stable machine-readable code carried by a failed, refused, or interrupted tool result. Each code
 * is a constant of one nested group enum — {@code ToolErrorCode.WORKSPACE.PATH_NOT_FOUND} — so the
 * error aspect is compile-time structure. The wire and persisted representation is the constant
 * {@link #name()} as a plain string; {@link #parse(String)} is the tolerant reader for persisted
 * payloads, preserving valid plugin-defined names.
 */
public interface ToolErrorCode {

    /**
     * Provides the stable symbolic name of this error.
     *
     * @return the enum constant or plugin-defined error name
     */
    @NonNull String name();

    /**
     * Serializes this error for wire and durable representations.
     *
     * @return the wire and persisted representation as a plain string
     */
    default @NonNull String id() {
        return name();
    }

    /**
     * Parses a wire or persisted code across every group, returning null for null, blank, or
     * malformed names so legacy history payloads never crash the reader.
     *
     * @param name wire or persisted error name
     * @return a known or plugin-defined code, or {@code null} when malformed
     */
    static ToolErrorCode parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var known = ToolErrorCodes.LOOKUP.get(name);
        if (known != null) return known;
        return name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}") ? new Named(name) : null;
    }

    /**
     * A plugin-defined code that needs no entry in a host enum.
     *
     * @param name valid stable plugin-defined error name
     */
    record Named(@NonNull String name) implements ToolErrorCode {
        /** Validates the stable plugin-defined name. */
        public Named {
            if (!name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"))
                throw new IllegalArgumentException("Invalid tool error name");
        }
    }

    /** Caller-correctable argument or content problems. */
    enum VALIDATION implements ToolErrorCode {

        /** The fetched page has no readable content. */
        EMPTY_CONTENT,

        /** {@code input_task} was called with no content, newline, or stdin close. */
        EMPTY_INPUT,

        /** Tool arguments failed validation. */
        INVALID_ARGUMENTS,

        /** A submitted answer failed citation validation. */
        INVALID_CITATION,

        /**
         * The move destination is invalid: it lies inside the source directory or its parent is not
         * an existing directory.
         */
        INVALID_DESTINATION,

        /** A submitted plan failed validation before execution. */
        INVALID_PLAN,

        /** {@code ask_user} questions failed validation. */
        INVALID_QUESTIONS,

        /** A file is not valid UTF-8. */
        INVALID_UTF8,

        /**
         * The named skill is not registered or its stored content failed integrity verification.
         */
        SKILL_NOT_FOUND,

        /** The requested tool name is not registered for this agent. */
        UNKNOWN_TOOL
    }

    /** Filesystem state and workspace mutation failures. */
    enum WORKSPACE implements ToolErrorCode {

        /** The move destination or write target already exists and is never overwritten. */
        ALREADY_EXISTS,

        /** Move source and destination are on different filesystems. */
        CROSS_FILESYSTEM_MOVE,

        /** Recursive delete preflight exceeded its entry-count or time safety limit. */
        DELETE_LIMIT_EXCEEDED,

        /** The directory is not empty and {@code recursive=true} was not given. */
        DIRECTORY_NOT_EMPTY,

        /** A file or content payload exceeds the per-call size limit. */
        FILE_TOO_LARGE,

        /** An I/O error stopped the operation. */
        IO_ERROR,

        /** The path is not a directory. */
        NOT_A_DIRECTORY,

        /** The path is not a regular file. */
        NOT_A_FILE,

        /** The path does not exist. */
        PATH_NOT_FOUND,

        /** The move source path does not exist. */
        SOURCE_NOT_FOUND,

        /** {@code targetContent} was not found in the selected line range. */
        TARGET_NOT_FOUND,

        /** {@code targetContent} occurs more than once in the selected line range. */
        TARGET_NOT_UNIQUE,

        /** The filesystem tree changed after authorization or during the operation. */
        TREE_CHANGED,

        /** A symbolic link or reparse point cannot be followed. */
        UNSAFE_LINK
    }

    /** Background task lifecycle failures. */
    enum TASK implements ToolErrorCode {

        /** A {@code run_command} command exited with a non-zero exit code. */
        COMMAND_FAILED,

        /** The task input queue exceeds its 256 KiB bound. */
        INPUT_QUEUE_FULL,

        /** The task's stdin is already closed. */
        STDIN_CLOSED,

        /** The background task does not exist. */
        TASK_NOT_FOUND,

        /** The background task is not running. */
        TASK_NOT_RUNNING
    }

    /** Network egress, remote tool, and credential failures. */
    enum NETWORK implements ToolErrorCode {

        /** A required imported credential or owning session is unavailable for the request. */
        CREDENTIAL_UNAVAILABLE,

        /** A redirect crossed origins, which requires a separately approved call. */
        CROSS_ORIGIN_REDIRECT,

        /** The destination is a private, loopback, link-local, or multicast address. */
        DESTINATION_REFUSED,

        /** A remote fetch or search failed with an unexpected error. */
        FETCH_FAILED,

        /** The destination host could not be resolved. */
        HOST_UNRESOLVED,

        /** The destination returned a non-success HTTP status. */
        HTTP_ERROR,

        /** The redirect response has no usable Location target. */
        INVALID_REDIRECT,

        /** The redirect target failed URL validation. */
        REDIRECT_REJECTED,

        /** A remote (MCP) tool reported an error result. */
        REMOTE_TOOL_FAILED,

        /** A remote fetch or search exceeded its time budget. */
        TIMEOUT,

        /** The fetch exceeded the redirect limit. */
        TOO_MANY_REDIRECTS
    }

    /** Bounded reader execution and document-state failures. */
    enum READER implements ToolErrorCode {
        /** The reader exhausted its execution budget without a validated result. */
        READER_BUDGET,
        /** A reader document must be fetched before this operation. */
        READER_DOCUMENT,
        /** The reader model ended without a validated result. */
        READER_MODEL,
        /** A reader observation exceeded its reading budget. */
        READER_OBSERVATION
    }

    /** Collaboration group state and operation failures. */
    enum GROUP implements ToolErrorCode {
        /** The calling agent has no active group. */
        NO_ACTIVE_GROUP,
        /** The group is no longer active. */
        NOT_ACTIVE,
        /** The group record disappeared while an operation was waiting. */
        RECORD_GONE,
        /** A collaboration operation was rejected by its domain rules. */
        REQUEST_REJECTED
    }

    /** Memory item state failures. */
    enum MEMORY implements ToolErrorCode {
        /** The embedding provider could not complete a memory operation. */
        MEMORY_EMBEDDING_FAILED,
        /** The memory item does not exist or is not owned by this caller. */
        MEMORY_NOT_FOUND
    }

    /** Protection and refusal situations. */
    enum POLICY implements ToolErrorCode {

        /** An interceptor or gateway policy blocked the call before execution. */
        CALL_BLOCKED,

        /** The path was refused because it contains a protected descendant. */
        DESCENDANT_REFUSED,

        /** The path was refused because it is protected. */
        PATH_PROTECTED,

        /** Protected file content could not be processed. */
        PROTECTED_INPUT_UNAVAILABLE
    }

    /** Cancellation, interruption, and missing-result situations. */
    enum LIFECYCLE implements ToolErrorCode {

        /** The web reader was cancelled while fetching or reading a page. */
        CANCELLED,

        /** The tool was interrupted while waiting, for example for a user answer. */
        TOOL_INTERRUPTED,

        /**
         * No tool result exists for an interrupted call; synthesized at prompt compile time and
         * never emitted by a tool.
         */
        TOOL_RESULT_MISSING,

        /** The user cancelled an {@code ask_user} question. */
        USER_CANCELLED
    }

    /** Result encoding and size failures. */
    enum RESULT implements ToolErrorCode {

        /** A tool result could not be encoded. */
        ENCODING_FAILED,

        /** A tool declared a JSON result but returned no valid JSON value. */
        INVALID_JSON,

        /** A JSON tool result exceeded the output limit and was omitted. */
        TOOL_RESULT_TOO_LARGE,

        /** The reader supports HTML and text documents only. */
        UNSUPPORTED_CONTENT
    }

    /** No more specific aspect applies. */
    enum GENERIC implements ToolErrorCode {

        /** A plugin call failed or its contract was not satisfied. */
        PLUGIN_CALL_FAILED,

        /** Generic tool failure with no more specific code. */
        TOOL_FAILURE
    }

    /** Session context failures. */
    enum SESSION implements ToolErrorCode {

        /** The call lacks an authenticated session or owner context. */
        NO_SESSION_CONTEXT
    }
}

/** Name index over every group enum; fails fast if two groups ever declare the same name. */
final class ToolErrorCodes {
    static final @NonNull Map<@NonNull String, @NonNull ToolErrorCode> LOOKUP = index();

    private ToolErrorCodes() {}

    private static ToolErrorCode @NonNull [] requireCodes(ToolErrorCode[] values) {
        if (values == null) throw new IllegalStateException("Missing tool error codes");
        return values;
    }

    private static @NonNull Map<@NonNull String, @NonNull ToolErrorCode> index() {
        Map<String, ToolErrorCode> lookup = new LinkedHashMap<>();
        ToolErrorCode[][] groups = {
            requireCodes(ToolErrorCode.VALIDATION.values()),
            requireCodes(ToolErrorCode.WORKSPACE.values()),
            requireCodes(ToolErrorCode.TASK.values()),
            requireCodes(ToolErrorCode.NETWORK.values()),
            requireCodes(ToolErrorCode.READER.values()),
            requireCodes(ToolErrorCode.GROUP.values()),
            requireCodes(ToolErrorCode.MEMORY.values()),
            requireCodes(ToolErrorCode.POLICY.values()),
            requireCodes(ToolErrorCode.LIFECYCLE.values()),
            requireCodes(ToolErrorCode.RESULT.values()),
            requireCodes(ToolErrorCode.GENERIC.values()),
            requireCodes(ToolErrorCode.SESSION.values())
        };
        for (ToolErrorCode[] group : groups) {
            for (ToolErrorCode code : group) {
                if (lookup.put(code.name(), code) != null) {
                    throw new IllegalStateException(
                            "Duplicate tool error code name: " + code.name());
                }
            }
        }
        return Map.copyOf(lookup);
    }
}
