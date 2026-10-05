package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.security.HostPathInput;
import top.focess.veto.vault.TestUsers;

class WorkspaceAdmissionPolicyTest {

    @Test
    void sandboxedAcceptsOnlyConfiguredMountDescendants(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        configuration(mount, DeployerPolicy.SANDBOXED),
                        emptyProtection(),
                        TestUsers.registry());

        Path admitted =
                policy.validateRoots(TestUsers.ALICE, List.of(mount.resolve("project").toString()))
                        .getFirst();
        assertEquals(
                HostPathInput.canonicalForCreation(mount.resolve("project"), "workspace root"),
                admitted);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateRoots(
                                TestUsers.ALICE, List.of(tempDir.resolve("outside").toString())));
    }

    @Test
    void tenantMapsEachUserIdBelowEveryConfiguredMount(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        configuration(mount, DeployerPolicy.TENANT),
                        emptyProtection(),
                        TestUsers.registry());

        Path admitted = policy.validateRoots(TestUsers.ALICE, List.of("/0/project")).getFirst();
        assertEquals(
                HostPathInput.canonicalForCreation(
                        mount.resolve("alice/project"), "workspace root"),
                admitted);
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots(TestUsers.ALICE, List.of("/0/project/nested")));
    }

    @Test
    void fullAccessCanonicalizesButDoesNotImposeWorkspaceContainment(
            @TempDir @NonNull Path tempDir) {
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        new DeployerPolicyConfiguration(),
                        mock(ProtectedSetResolver.class),
                        TestUsers.registry());
        Path target = tempDir.resolve("arbitrary/project");

        assertEquals(
                HostPathInput.canonicalForCreation(target, "workspace root"),
                policy.validateRoots(TestUsers.ALICE, List.of(target.toString())).getFirst());
    }

    @Test
    void protectedRootsAndChildrenAreRejectedButTheirWorkspaceParentIsAllowed(
            @TempDir @NonNull Path tempDir) throws Exception {
        var protectedPath = tempDir.resolve("alice");
        var resolver = mock(ProtectedSetResolver.class);
        when(resolver.resolve(any(), any(UUID.class), any()))
                .thenReturn(new ProtectedSet(Set.of(protectedPath)));
        for (var mode :
                List.of(
                        DeployerPolicy.PROTECTED,
                        DeployerPolicy.SANDBOXED,
                        DeployerPolicy.TENANT)) {
            var policy =
                    new WorkspaceAdmissionPolicy(
                            configuration(tempDir, mode), resolver, TestUsers.registry());
            UUID owner = TestUsers.ALICE;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            policy.validateRoots(
                                    owner,
                                    List.of(
                                            mode == DeployerPolicy.TENANT
                                                    ? "/0/project"
                                                    : protectedPath.toString())));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            policy.validateRoots(
                                    owner,
                                    List.of(
                                            mode == DeployerPolicy.TENANT
                                                    ? "/0/child"
                                                    : protectedPath
                                                            .resolve("project")
                                                            .toString())));
        }
        var policy =
                new WorkspaceAdmissionPolicy(
                        configuration(tempDir, DeployerPolicy.PROTECTED),
                        resolver,
                        TestUsers.registry());
        assertEquals(
                tempDir.toRealPath(),
                policy.validateRoots(TestUsers.ALICE, List.of(tempDir.toString())).getFirst());
    }

    @Test
    void filesystemRootIsValidButBlankIsNot(@TempDir @NonNull Path tempDir) throws Exception {
        var policy =
                new WorkspaceAdmissionPolicy(
                        new DeployerPolicyConfiguration(),
                        mock(ProtectedSetResolver.class),
                        TestUsers.registry());
        var root = tempDir.toAbsolutePath().getRoot();
        if (root == null) throw new AssertionError("absolute path must have a filesystem root");
        assertEquals(
                root.toRealPath(),
                policy.validateRoots(TestUsers.ALICE, List.of(root.toString())).getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots(TestUsers.ALICE, List.of(" ")));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots(TestUsers.ALICE, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateRoots(
                                TestUsers.ALICE,
                                List.of(tempDir.resolve("comma,name").toString())));
    }

    private static @NonNull DeployerPolicyConfiguration configuration(
            @NonNull Path base, @NonNull DeployerPolicy mode) {
        var configuration = new DeployerPolicyConfiguration();
        configuration.setDeployerPolicy(mode);
        configuration.getSandboxed().setRoots(List.of(base.toString()));
        configuration.getTenant().setRoots(List.of(base.toString()));
        return configuration;
    }

    @Test
    void tenantUsesLogicalDirectChildSelectionAndHidesHostMapping(@TempDir @NonNull Path base) {
        var policy =
                new WorkspaceAdmissionPolicy(
                        configuration(base, DeployerPolicy.TENANT),
                        emptyProtection(),
                        TestUsers.registry());
        var selected = policy.validateRoots(TestUsers.ALICE, List.of("/0/project")).getFirst();
        assertEquals(base.resolve("alice/project"), selected);
        assertEquals("/0/project", policy.toClientPath(TestUsers.ALICE, selected));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots(TestUsers.ALICE, List.of("/0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots(TestUsers.ALICE, List.of("/0/project/nested")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateRoots(
                                TestUsers.ALICE,
                                List.of(base.resolve("alice/project").toString())));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.fromClientPath(TestUsers.ALICE, "/0/../bob"));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.fromClientPath(TestUsers.ALICE, "/0/project/"));
    }

    private static @NonNull ProtectedSetResolver emptyProtection() {
        var resolver = mock(ProtectedSetResolver.class);
        when(resolver.resolve(any(), any(UUID.class), any())).thenReturn(ProtectedSet.empty());
        return resolver;
    }
}
