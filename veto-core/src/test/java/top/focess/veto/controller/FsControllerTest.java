package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.controller.dto.DirectoryCreatedResponse;
import top.focess.veto.controller.dto.DirectoryListingResponse;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class FsControllerTest {
    private static @NonNull FsController authenticated() {
        KeysteadVault vault = mock(KeysteadVault.class);
        when(vault.currentUser()).thenReturn("test-user");
        return controller(vault, new DeployerPolicyConfiguration(), List.of());
    }

    private static @NonNull FsController controller(
            @NonNull KeysteadVault vault,
            @NonNull DeployerPolicyConfiguration configuration,
            @NonNull List<@NonNull Path> occupied) {
        var resolver = mock(ProtectedSetResolver.class);
        when(resolver.resolve(any(), anyString(), any())).thenReturn(ProtectedSet.empty());
        var policy = new WorkspaceAdmissionPolicy(configuration, resolver);
        var sessions = mock(SessionRepository.class);
        when(sessions.claimedRootsExcept(anyString())).thenReturn(occupied);
        var service = mock(SessionService.class);
        try {
            when(service.createWorkspaceDirectory(anyString(), any(), anyString()))
                    .thenAnswer(
                            call -> {
                                Path parent = call.getArgument(1);
                                String name = call.getArgument(2);
                                if (parent == null || name == null)
                                    throw new AssertionError("missing directory arguments");
                                Files.createDirectories(parent);
                                return Files.createDirectory(parent.resolve(name));
                            });
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return new FsController(vault, policy, sessions, service);
    }

    private static @NonNull FsController scoped(
            @NonNull Path base,
            @NonNull DeployerPolicy mode,
            @NonNull List<@NonNull Path> occupied) {
        var vault = mock(KeysteadVault.class);
        when(vault.currentUser()).thenReturn("alice");
        var configuration = new DeployerPolicyConfiguration();
        configuration.setDeployerPolicy(mode);
        configuration.getSandboxed().setRoots(List.of(base.toString()));
        configuration.getTenant().setRoots(List.of(base.toString()));
        return controller(vault, configuration, occupied);
    }

    @Test
    void sandboxShowsClaimsWithoutAllowingExpansionOrCreation(@TempDir @NonNull Path base)
            throws Exception {
        var foreign = Files.createDirectory(base.resolve("claimed"));
        Files.createDirectory(foreign.resolve("private"));
        var available = Files.createDirectory(base.resolve("available"));
        var controller = scoped(base, DeployerPolicy.SANDBOXED, List.of(foreign));
        var response = controller.browse(base.toString());
        var listing = (DirectoryListingResponse) response.getBody();
        if (listing == null) throw new AssertionError("missing directory listing");
        var claimed =
                listing.entries().stream()
                        .filter(entry -> entry.name().equals("claimed"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(claimed.declared());
        assertFalse(claimed.expandable());
        assertFalse(claimed.selectable());
        assertTrue(
                listing.entries().stream()
                        .anyMatch(
                                entry ->
                                        entry.path().equals(available.toString())
                                                && entry.selectable()));
        var denied =
                assertThrows(
                        ResponseStatusException.class, () -> controller.browse(foreign.toString()));
        assertEquals(403, denied.getStatusCode().value());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        controller.createDirectory(
                                new FsController.CreateDirectoryRequest(
                                        foreign.toString(), "blocked")));
        assertFalse(Files.exists(foreign.resolve("blocked")));
    }

    @Test
    void symlinkCannotRevealPrivateDescendantsOfForeignClaim(@TempDir @NonNull Path base)
            throws Exception {
        var foreign = Files.createDirectory(base.resolve("claimed"));
        var privateDirectory = Files.createDirectory(foreign.resolve("private"));
        var publicDirectory = Files.createDirectory(base.resolve("public"));
        try {
            Files.createSymbolicLink(publicDirectory.resolve("reveal"), privateDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(
                    false,
                    "Directory symlink creation unavailable: " + e.getClass().getSimpleName());
        }
        var controller = scoped(base, DeployerPolicy.SANDBOXED, List.of(foreign));
        var listing =
                (DirectoryListingResponse) controller.browse(publicDirectory.toString()).getBody();
        if (listing == null) throw new AssertionError("missing directory listing");
        assertTrue(listing.entries().isEmpty());
    }

    @Test
    void tenantExposesOnlyMappedWorkspaceLevel(@TempDir @NonNull Path base) throws Exception {
        var controller = scoped(base, DeployerPolicy.TENANT, List.of());
        var roots = (DirectoryListingResponse) controller.browse(null).getBody();
        if (roots == null) throw new AssertionError("missing directory listing");
        assertEquals("/0", roots.entries().getFirst().path());
        assertFalse(roots.entries().getFirst().selectable());
        var empty = (DirectoryListingResponse) controller.browse("/0").getBody();
        if (empty == null) throw new AssertionError("missing directory listing");
        assertTrue(empty.canCreate());
        assertTrue(empty.entries().isEmpty());
        assertFalse(Files.exists(base.resolve("alice")));
        var created =
                controller.createDirectory(
                        new FsController.CreateDirectoryRequest("/0", "project"));
        assertEquals(new DirectoryCreatedResponse("/0/project", false), created.getBody());
        var listing = (DirectoryListingResponse) controller.browse("/0").getBody();
        if (listing == null) throw new AssertionError("missing directory listing");
        assertEquals("/0/project", listing.entries().getFirst().path());
        assertTrue(listing.entries().getFirst().selectable());
        assertFalse(listing.entries().getFirst().expandable());
        assertThrows(ResponseStatusException.class, () -> controller.browse("/0/project"));
        assertThrows(ResponseStatusException.class, () -> controller.browse(base.toString()));
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
                new DirectoryCreatedResponse(
                        directory.toRealPath().resolve("新工作区").toString(), true),
                response.getBody());
    }

    @Test
    void requiresAuthenticationBeforeCreating(@TempDir @NonNull Path directory) {
        KeysteadVault vault = mock(KeysteadVault.class);
        var response =
                controller(vault, new DeployerPolicyConfiguration(), List.of())
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
