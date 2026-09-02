package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.screening.DeployerPolicy;

class WorkspaceAdmissionPolicyTest {

    @Test
    void sandboxedAcceptsOnlyConfiguredMountDescendants(@TempDir @NonNull Path tempDir) {
        Path mount = tempDir.resolve("mount");
        WorkspaceAdmissionPolicy policy =
                new WorkspaceAdmissionPolicy(List.of(mount), DeployerPolicy.SANDBOXED);

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
                new WorkspaceAdmissionPolicy(List.of(mount), DeployerPolicy.TENANT);

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
                new WorkspaceAdmissionPolicy(List.of(), DeployerPolicy.FULL_ACCESS);
        Path target = tempDir.resolve("arbitrary/project");

        assertEquals(
                WorkspaceAdmissionPolicy.canonicalForCreation(target, "workspace root"),
                policy.admit("alice", target.toString()).getFirst());
    }
}
