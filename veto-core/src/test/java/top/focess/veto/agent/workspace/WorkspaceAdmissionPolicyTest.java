package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.security.HostPathInput;

class WorkspaceAdmissionPolicyTest {

    @Test
    void sandboxedAcceptsOnlyConfiguredMountDescendants(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        configuration(mount, DeployerPolicy.SANDBOXED), emptyProtection());

        Path admitted =
                policy.validateRoots("alice", List.of(mount.resolve("project").toString()))
                        .getFirst();
        assertEquals(
                HostPathInput.canonicalForCreation(mount.resolve("project"), "workspace root"),
                admitted);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateRoots(
                                "alice", List.of(tempDir.resolve("outside").toString())));
    }

    @Test
    void tenantMapsEachOwnerBelowEveryConfiguredMount(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        configuration(mount, DeployerPolicy.TENANT), emptyProtection());

        Path admitted = policy.validateRoots("alice", List.of("/0/project")).getFirst();
        assertEquals(
                HostPathInput.canonicalForCreation(
                        mount.resolve("alice/project"), "workspace root"),
                admitted);
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots("alice", List.of("/0/project/nested")));
    }

    @Test
    void fullAccessCanonicalizesButDoesNotImposeWorkspaceContainment(
            @TempDir @NonNull Path tempDir) {
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        new DeployerPolicyConfiguration(), mock(ProtectedSetResolver.class));
        Path target = tempDir.resolve("arbitrary/project");

        assertEquals(
                HostPathInput.canonicalForCreation(target, "workspace root"),
                policy.validateRoots("alice", List.of(target.toString())).getFirst());
    }

    @Test
    void protectedRootsAndChildrenAreRejectedButTheirWorkspaceParentIsAllowed(
            @TempDir @NonNull Path tempDir) throws Exception {
        var protectedPath = tempDir.resolve("secrets");
        var resolver = mock(ProtectedSetResolver.class);
        when(resolver.resolve(any(), anyString(), any()))
                .thenReturn(new ProtectedSet(Set.of(protectedPath)));
        for (var mode :
                List.of(
                        DeployerPolicy.PROTECTED,
                        DeployerPolicy.SANDBOXED,
                        DeployerPolicy.TENANT)) {
            var policy = new WorkspaceAdmissionPolicy(configuration(tempDir, mode), resolver);
            String owner = "secrets";
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
                        configuration(tempDir, DeployerPolicy.PROTECTED), resolver);
        assertEquals(
                tempDir.toRealPath(),
                policy.validateRoots("alice", List.of(tempDir.toString())).getFirst());
    }

    @Test
    void filesystemRootIsValidButBlankIsNot(@TempDir @NonNull Path tempDir) throws Exception {
        var policy =
                new WorkspaceAdmissionPolicy(
                        new DeployerPolicyConfiguration(), mock(ProtectedSetResolver.class));
        var root = tempDir.toAbsolutePath().getRoot();
        if (root == null) throw new AssertionError("absolute path must have a filesystem root");
        assertEquals(
                root.toRealPath(),
                policy.validateRoots("alice", List.of(root.toString())).getFirst());
        assertThrows(
                IllegalArgumentException.class, () -> policy.validateRoots("alice", List.of(" ")));
        assertThrows(
                IllegalArgumentException.class, () -> policy.validateRoots("alice", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateRoots(
                                "alice", List.of(tempDir.resolve("comma,name").toString())));
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
                        configuration(base, DeployerPolicy.TENANT), emptyProtection());
        var selected = policy.validateRoots("alice", List.of("/0/project")).getFirst();
        assertEquals(base.resolve("alice/project"), selected);
        assertEquals("/0/project", policy.toClientPath("alice", selected));
        assertThrows(
                IllegalArgumentException.class, () -> policy.validateRoots("alice", List.of("/0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateRoots("alice", List.of("/0/project/nested")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateRoots(
                                "alice", List.of(base.resolve("alice/project").toString())));
        assertThrows(
                IllegalArgumentException.class, () -> policy.fromClientPath("alice", "/0/../bob"));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.fromClientPath("alice", "/0/project/"));
    }

    private static @NonNull ProtectedSetResolver emptyProtection() {
        var resolver = mock(ProtectedSetResolver.class);
        when(resolver.resolve(any(), anyString(), any())).thenReturn(ProtectedSet.empty());
        return resolver;
    }
}
