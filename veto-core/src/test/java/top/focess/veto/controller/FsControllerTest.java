package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.vault.KeysteadVault;

class FsControllerTest {
    private static @NonNull FsController authenticated() {
        @NonNull KeysteadVault vault = mock();
        when(vault.currentUser()).thenReturn("test-user");
        return new FsController(vault);
    }

    @Test
    void createsOneChildAndReturnsItsPath(@TempDir @NonNull Path directory) throws Exception {
        var response =
                authenticated()
                        .createDirectory(
                                new FsController.CreateDirectoryRequest(
                                        directory.toString(), "新工作区"));
        assertEquals(201, response.getStatusCode().value());
        assertTrue(Files.isDirectory(directory.resolve("新工作区")));
        assertEquals(
                Map.of("path", directory.toRealPath().resolve("新工作区").toString()),
                response.getBody());
    }

    @Test
    void requiresAuthenticationBeforeCreating(@TempDir @NonNull Path directory) {
        @NonNull KeysteadVault vault = mock();
        var response =
                new FsController(vault)
                        .createDirectory(
                                new FsController.CreateDirectoryRequest(
                                        directory.toString(), "blocked"));
        assertEquals(401, response.getStatusCode().value());
        assertFalse(Files.exists(directory.resolve("blocked")));
    }

    @Test
    void rejectsTraversalAndNestedNames(@TempDir @NonNull Path directory) {
        for (String name :
                new String[] {
                    "",
                    ".",
                    "..",
                    "../escape",
                    "nested/child",
                    "nested\\child",
                    "C:escape",
                    "trailing.",
                    " trailing"
                }) {
            assertEquals(
                    400,
                    authenticated()
                            .createDirectory(
                                    new FsController.CreateDirectoryRequest(
                                            directory.toString(), name))
                            .getStatusCode()
                            .value());
        }
    }

    @Test
    void neverOverwritesExistingEntries(@TempDir @NonNull Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("existing"), "keep me");
        var response =
                authenticated()
                        .createDirectory(
                                new FsController.CreateDirectoryRequest(
                                        directory.toString(), "existing"));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("keep me", Files.readString(file));
        Files.createDirectory(directory.resolve("folder"));
        assertEquals(
                409,
                authenticated()
                        .createDirectory(
                                new FsController.CreateDirectoryRequest(
                                        directory.toString(), "folder"))
                        .getStatusCode()
                        .value());
    }

    @Test
    void requiresAnExistingAbsoluteParent(@TempDir @NonNull Path directory) {
        for (String parent :
                new String[] {
                    "relative",
                    directory.resolve("missing").toString(),
                    directory.resolve("..").toString()
                }) {
            assertEquals(
                    400,
                    authenticated()
                            .createDirectory(
                                    new FsController.CreateDirectoryRequest(parent, "child"))
                            .getStatusCode()
                            .value());
        }
    }
}
