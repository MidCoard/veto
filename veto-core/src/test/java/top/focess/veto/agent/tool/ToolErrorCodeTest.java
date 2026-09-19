package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.util.Nullness;

class ToolErrorCodeTest {

    @Test
    void parseRoundTripsEveryConstantAcrossAllGroups() {
        for (ToolErrorCode code : allCodes()) {
            assertSame(code, Nullness.requireNonNull(ToolErrorCode.parse(code.name())));
            assertEquals(code.name(), code.id());
        }
    }

    @Test
    void parseToleratesUnknownAndBlankNames() {
        assertNull(ToolErrorCode.parse(null));
        assertNull(ToolErrorCode.parse(""));
        assertNull(ToolErrorCode.parse("   "));
        assertNull(ToolErrorCode.parse("NO_SUCH_CODE"));
        assertNull(ToolErrorCode.parse("io_error"));
        assertNull(ToolErrorCode.parse("FILE_EXISTS"), "merged-away names are not aliased");
    }

    @Test
    void everyConstantLivesInItsExpectedGroup() {
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.VALIDATION.values()),
                "EMPTY_CONTENT",
                "EMPTY_INPUT",
                "INVALID_ARGUMENTS",
                "INVALID_CITATION",
                "INVALID_DESTINATION",
                "INVALID_PLAN",
                "INVALID_QUESTIONS",
                "INVALID_UTF8",
                "SKILL_NOT_FOUND",
                "UNKNOWN_TOOL");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.WORKSPACE.values()),
                "ALREADY_EXISTS",
                "CROSS_FILESYSTEM_MOVE",
                "DELETE_LIMIT_EXCEEDED",
                "DIRECTORY_NOT_EMPTY",
                "FILE_TOO_LARGE",
                "IO_ERROR",
                "NOT_A_DIRECTORY",
                "NOT_A_FILE",
                "PATH_NOT_FOUND",
                "SOURCE_NOT_FOUND",
                "TARGET_NOT_FOUND",
                "TARGET_NOT_UNIQUE",
                "TREE_CHANGED",
                "UNSAFE_LINK");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.TASK.values()),
                "COMMAND_FAILED",
                "INPUT_QUEUE_FULL",
                "STDIN_CLOSED",
                "TASK_NOT_FOUND",
                "TASK_NOT_RUNNING");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.NETWORK.values()),
                "AUTHENTICATED_READ_FAILED",
                "CREDENTIAL_UNAVAILABLE",
                "CROSS_ORIGIN_REDIRECT",
                "DESTINATION_REFUSED",
                "FETCH_FAILED",
                "GITHUB_HTTP_ERROR",
                "HOST_UNRESOLVED",
                "HTTP_ERROR",
                "INVALID_REDIRECT",
                "INVALID_REPOSITORY",
                "REDIRECT_REJECTED",
                "REMOTE_TOOL_FAILED",
                "TIMEOUT",
                "TOO_MANY_REDIRECTS");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.READER.values()),
                "READER_BUDGET",
                "READER_DOCUMENT",
                "READER_IDENTITY",
                "READER_MODEL",
                "READER_OBSERVATION",
                "READER_OUTPUT",
                "READER_TIMEOUT");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.POLICY.values()),
                "CALL_BLOCKED",
                "DESCENDANT_REFUSED",
                "PATH_PROTECTED",
                "PROTECTED_INPUT_UNAVAILABLE");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.LIFECYCLE.values()),
                "CANCELLED",
                "TOOL_INTERRUPTED",
                "TOOL_RESULT_MISSING",
                "USER_CANCELLED");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.RESULT.values()),
                "ENCODING_FAILED",
                "INVALID_JSON",
                "TOOL_RESULT_TOO_LARGE",
                "UNSUPPORTED_CONTENT");
        assertGroup(Nullness.requireNonNull(ToolErrorCode.GENERIC.values()), "TOOL_FAILURE");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.GROUP.values()),
                "NO_ACTIVE_GROUP",
                "NOT_ACTIVE",
                "RECORD_GONE",
                "REQUEST_REJECTED");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.MEMORY.values()), "NOT_FOUND", "TOO_LARGE");
        assertGroup(
                Nullness.requireNonNull(ToolErrorCode.MONITOR.values()),
                "GROUP_MANAGED",
                "LIMIT_EXCEEDED",
                "UNKNOWN");
        assertGroup(Nullness.requireNonNull(ToolErrorCode.SESSION.values()), "NO_SESSION_CONTEXT");
    }

    @Test
    void constantNamesAreUniqueAcrossGroups() {
        List<ToolErrorCode> codes = allCodes();
        Set<String> names = new HashSet<>();
        for (ToolErrorCode code : codes) {
            names.add(code.name());
        }
        // The ByName index also throws on duplicates at class initialization.
        assertEquals(codes.size(), names.size());
    }

    private static void assertGroup(
            ToolErrorCode @NonNull [] group, @NonNull String @NonNull ... expectedNames) {
        String[] actual = new String[group.length];
        for (int i = 0; i < group.length; i++) {
            actual[i] = group[i].name();
        }
        assertArrayEquals(expectedNames, actual);
    }

    private static @NonNull List<@NonNull ToolErrorCode> allCodes() {
        List<ToolErrorCode> codes = new ArrayList<>();
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.VALIDATION.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.WORKSPACE.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.TASK.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.NETWORK.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.READER.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.POLICY.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.LIFECYCLE.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.RESULT.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.GENERIC.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.GROUP.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.MEMORY.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.MONITOR.values())));
        codes.addAll(List.of(Nullness.requireNonNull(ToolErrorCode.SESSION.values())));
        return codes;
    }
}
