package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * Stable machine-readable code carried by a failed, refused, or interrupted {@link ToolResult}. The
 * wire and persisted representation is the enum {@link #name()} as a plain string.
 */
public enum ToolErrorCode {

    /** An authenticated GitHub repository read produced no usable answer. */
    AUTHENTICATED_READ_FAILED,

    /** The web reader was cancelled while fetching or reading a page. */
    CANCELLED,

    /** A {@code run_command} command exited with a non-zero exit code. */
    COMMAND_FAILED,

    /** A GitHub credential or owning session is unavailable for the request. */
    CREDENTIAL_UNAVAILABLE,

    /** Move source and destination are on different filesystems. */
    CROSS_FILESYSTEM_MOVE,

    /** Recursive delete preflight exceeded its entry-count or time safety limit. */
    DELETE_LIMIT_EXCEEDED,

    /** The path was refused because it contains a protected descendant. */
    DESCENDANT_REFUSED,

    /** The move destination already exists and is never overwritten. */
    DESTINATION_EXISTS,

    /** The directory is not empty and {@code recursive=true} was not given. */
    DIRECTORY_NOT_EMPTY,

    /** The fetched page has no readable content. */
    EMPTY_CONTENT,

    /** {@code input_task} was called with no content, newline, or stdin close. */
    EMPTY_INPUT,

    /** {@code write_to_file} found an existing file while {@code overwrite} is false. */
    FILE_EXISTS,

    /** A file or content payload exceeds the 16 MiB limit. */
    FILE_TOO_LARGE,

    /** A GitHub repository request returned an HTTP error. */
    GITHUB_HTTP_ERROR,

    /** The task input queue exceeds its 256 KiB bound. */
    INPUT_QUEUE_FULL,

    /** A single {@code input_task} payload exceeds the 64 KiB limit. */
    INPUT_TOO_LARGE,

    /** Tool arguments failed validation. */
    INVALID_ARGUMENTS,

    /** A submitted answer failed citation validation. */
    INVALID_CITATION,

    /**
     * The move destination is invalid: it lies inside the source directory or its parent is not an
     * existing directory.
     */
    INVALID_DESTINATION,

    /** A glob or include pattern is invalid. */
    INVALID_PATTERN,

    /** A submitted plan failed validation before execution. */
    INVALID_PLAN,

    /** A {@code grep_search} query is empty. */
    INVALID_QUERY,

    /** {@code ask_user} questions failed validation. */
    INVALID_QUESTIONS,

    /** A GitHub repository owner or name is invalid. */
    INVALID_REPOSITORY,

    /** A file is not valid UTF-8. */
    INVALID_UTF8,

    /** An I/O error stopped the operation. */
    IO_ERROR,

    /** The path is not a directory. */
    NOT_A_DIRECTORY,

    /** The path is not a regular file. */
    NOT_A_FILE,

    /** The path does not exist. */
    PATH_NOT_FOUND,

    /** The path was refused because it is protected. */
    PATH_PROTECTED,

    /** Protected file content could not be processed. */
    PROTECTED_INPUT_UNAVAILABLE,

    /** The web reader exhausted its execution budget without a validated result. */
    READER_BUDGET,

    /** The web reader was asked to read a page that has not been fetched. */
    READER_DOCUMENT,

    /** The web reader lacks an authenticated session owner. */
    READER_IDENTITY,

    /** The web reader model failed to produce a validated result. */
    READER_MODEL,

    /** The web reader has no budget left for the requested observation. */
    READER_OBSERVATION,

    /** The web reader result could not be encoded. */
    READER_OUTPUT,

    /** The web reader exceeded its time budget. */
    READER_TIMEOUT,

    /** A remote (MCP) tool reported an error result. */
    REMOTE_TOOL_FAILED,

    /** The move source path does not exist. */
    SOURCE_NOT_FOUND,

    /** The task's stdin is already closed. */
    STDIN_CLOSED,

    /** {@code targetContent} was not found in the selected line range. */
    TARGET_NOT_FOUND,

    /** {@code targetContent} occurs more than once in the selected line range. */
    TARGET_NOT_UNIQUE,

    /** The background task does not exist. */
    TASK_NOT_FOUND,

    /** The background task is not running. */
    TASK_NOT_RUNNING,

    /** Generic tool failure with no more specific code. */
    TOOL_FAILURE,

    /** The tool was interrupted while waiting, for example for a user answer. */
    TOOL_INTERRUPTED,

    /**
     * No tool result exists for an interrupted call; synthesized at prompt compile time and never
     * emitted by a tool.
     */
    TOOL_RESULT_MISSING,

    /** A JSON tool result exceeded the output limit and was omitted. */
    TOOL_RESULT_TOO_LARGE,

    /** The filesystem tree changed after authorization or during the operation. */
    TREE_CHANGED,

    /** A symbolic link or reparse point cannot be followed. */
    UNSAFE_LINK,

    /** The reader supports HTML and text documents only. */
    UNSUPPORTED_CONTENT,

    /** The user cancelled an {@code ask_user} question. */
    USER_CANCELLED;

    /**
     * Parses a wire or persisted code, returning null for null, blank, or unrecognized names so
     * legacy history payloads never crash the reader.
     */
    public static ToolErrorCode parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The wire and persisted representation: the enum name as a plain string. */
    public @NonNull String id() {
        return name();
    }
}
