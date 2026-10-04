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
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ProtectedSetResolver;

class WorkspaceAdmissionPolicyTest {

    @Test
    void sandboxedAcceptsOnlyConfiguredMountDescendants(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        List.of(mount), DeployerPolicy.SANDBOXED, emptyProtection());

        Path admitted = policy.admit("alice", mount.resolve("project").toString()).getFirst();
        assertEquals(
                WorkspaceAdmissionPolicy.canonicalForCreation(
                        mount.resolve("project"), "workspace root"),
                admitted);
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.admit("alice", tempDir.resolve("outside").toString()));
    }

    @Test
    void tenantMapsEachOwnerBelowEveryConfiguredMount(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        List.of(mount), DeployerPolicy.TENANT, emptyProtection());

        Path admitted = policy.admit("alice", mount.resolve("alice/project").toString()).getFirst();
        assertEquals(
                WorkspaceAdmissionPolicy.canonicalForCreation(
                        mount.resolve("alice/project"), "workspace root"),
                admitted);
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.admit("alice", mount.resolve("bob/project").toString()));
    }

    @Test
    void fullAccessCanonicalizesButDoesNotImposeWorkspaceContainment(
            @TempDir @NonNull Path tempDir) {
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(
                        List.of(), DeployerPolicy.FULL_ACCESS, mock(ProtectedSetResolver.class));
        Path target = tempDir.resolve("arbitrary/project");

        assertEquals(
                WorkspaceAdmissionPolicy.canonicalForCreation(target, "workspace root"),
                policy.admit("alice", target.toString()).getFirst());
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
            var policy = new WorkspaceAdmissionPolicy(List.of(tempDir), mode, resolver);
            String owner = "secrets";
            assertThrows(
                    IllegalArgumentException.class,
                    () -> policy.admit(owner, protectedPath.toString()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> policy.admit(owner, protectedPath.resolve("project").toString()));
        }
        var policy = new WorkspaceAdmissionPolicy(List.of(), DeployerPolicy.PROTECTED, resolver);
        assertEquals(tempDir.toRealPath(), policy.admit("alice", tempDir.toString()).getFirst());
    }

    @Test
    void filesystemRootIsValidButBlankIsNot(@TempDir @NonNull Path tempDir) throws Exception {
        var policy =
                new WorkspaceAdmissionPolicy(
                        List.of(), DeployerPolicy.FULL_ACCESS, mock(ProtectedSetResolver.class));
        var root = tempDir.toAbsolutePath().getRoot();
        if (root == null) throw new AssertionError("absolute path must have a filesystem root");
        assertEquals(root.toRealPath(), policy.admit("alice", root.toString()).getFirst());
        assertThrows(IllegalArgumentException.class, () -> policy.admit("alice", " "));
    }

    private static @NonNull ProtectedSetResolver emptyProtection() {
        var resolver = mock(ProtectedSetResolver.class);
        when(resolver.resolve(any(), anyString(), any())).thenReturn(ProtectedSet.empty());
        return resolver;
    }
}
