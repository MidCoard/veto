package top.focess.veto.sandbox;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.mcp.ToolDocs;

/** Windows AppContainer identity and inheritable workspace ACL provisioner. */
final class WindowsWorkspaceSecurity {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.sandbox.WindowsWorkspaceSecurity");

    private static final int HRESULT_ALREADY_EXISTS = 0x800700B7;
    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int FILE_ALL_ACCESS = 0x001F01FF;
    private static final int FILE_GENERIC_READ_EXECUTE = 0x001200A9;
    private static final int DIRECTORY_TRAVERSE_AND_READ_ATTRIBUTES = 0x001200A0;
    private static final int GRANT_ACCESS = 1;
    private static final int DENY_ACCESS = 3;
    private static final int SUB_CONTAINERS_AND_OBJECTS_INHERIT = 0x3;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_UNKNOWN = 0;
    private static final @NonNull String ALL_APPLICATION_PACKAGES_SID = "S-1-15-2-1";
    private static final @NonNull String ALL_RESTRICTED_APPLICATION_PACKAGES_SID = "S-1-15-2-2";
    private final @NonNull WindowsAclApi api;
    private final @NonNull WindowsAppContainerApi appContainerApi;
    private final @NonNull Map<@NonNull Path, @NonNull SyntheticMask> syntheticMasks =
            new HashMap<>();

    WindowsWorkspaceSecurity() {
        api = Native.load("advapi32", ToolDocs.nonNullClass(WindowsAclApi.class));
        appContainerApi =
                Native.load("userenv", ToolDocs.nonNullClass(WindowsAppContainerApi.class));
    }

    boolean isAvailable() {
        return true;
    }

    synchronized @NonNull String provision(@NonNull SandboxProfile profile) {
        Path workspace = profile.workspaceRoot();
        String containerName = appContainerName(workspace);
        PointerByReference sid = new PointerByReference();
        PointerByReference allApplicationPackagesSid = new PointerByReference();
        PointerByReference allRestrictedApplicationPackagesSid = new PointerByReference();
        int result =
                appContainerApi.CreateAppContainerProfile(
                        new WString(containerName),
                        new WString("Veto sandbox"),
                        new WString("Veto workspace-isolated command sandbox"),
                        null,
                        0,
                        sid);
        if (result == HRESULT_ALREADY_EXISTS) {
            result =
                    appContainerApi.DeriveAppContainerSidFromAppContainerName(
                            new WString(containerName), sid);
        }
        if (result != 0 || sid.getValue() == null) {
            throw new IllegalStateException(
                    "DeriveAppContainerSidFromAppContainerName failed (HRESULT=" + result + ")");
        }
        if (!api.ConvertStringSidToSidW(
                new WString(ALL_APPLICATION_PACKAGES_SID), allApplicationPackagesSid)) {
            api.FreeSid(sid.getValue());
            throw new IllegalStateException(
                    "ConvertStringSidToSidW(All Application Packages) failed (Win32 error="
                            + Kernel32.INSTANCE.GetLastError()
                            + ")");
        }
        if (!api.ConvertStringSidToSidW(
                new WString(ALL_RESTRICTED_APPLICATION_PACKAGES_SID),
                allRestrictedApplicationPackagesSid)) {
            localFree(allApplicationPackagesSid.getValue());
            api.FreeSid(sid.getValue());
            throw new IllegalStateException(
                    "ConvertStringSidToSidW(All Restricted Application Packages) failed (Win32 error="
                            + Kernel32.INSTANCE.GetLastError()
                            + ")");
        }
        List<Path> acquiredMasks = acquireCreationMasks(profile);
        boolean completed = false;
        try {
            // Read/execute compatibility is projected lazily for the executable selected by this
            // invocation. Eagerly touching every PATH entry is both unnecessarily broad and very
            // expensive on developer machines with large toolchains.
            for (Path root : profile.readWriteExecuteRoots()) {
                tryGrantCompatibilityRoot(root, sid.getValue(), FILE_ALL_ACCESS);
            }
            grantAccess(
                    workspace, sid.getValue(), FILE_ALL_ACCESS, SUB_CONTAINERS_AND_OBJECTS_INHERIT);
            String appContainerSid = sidString(sid.getValue());
            for (Path deniedPath : profile.deniedPaths()) {
                Path denied = deniedPath.toAbsolutePath().normalize();
                if (Files.exists(denied)) {
                    denyAccess(
                            denied,
                            sid.getValue(),
                            FILE_ALL_ACCESS,
                            SUB_CONTAINERS_AND_OBJECTS_INHERIT);
                    denyAccess(
                            denied,
                            allApplicationPackagesSid.getValue(),
                            FILE_ALL_ACCESS,
                            SUB_CONTAINERS_AND_OBJECTS_INHERIT);
                    denyAccess(
                            denied,
                            allRestrictedApplicationPackagesSid.getValue(),
                            FILE_ALL_ACCESS,
                            SUB_CONTAINERS_AND_OBJECTS_INHERIT);
                    removeSandboxAccess(denied, Set.of(appContainerSid));
                }
            }
            completed = true;
            return containerName;
        } finally {
            localFree(allRestrictedApplicationPackagesSid.getValue());
            localFree(allApplicationPackagesSid.getValue());
            api.FreeSid(sid.getValue());
            if (!completed) {
                releaseCreationMasks(acquiredMasks);
            }
        }
    }

    /**
     * Makes only the executable selected for this invocation reachable by the workspace's stable
     * AppContainer identity. PATH remains a lookup mechanism; it is not treated as a blanket ACL
     * grant.
     */
    synchronized void provisionExecutable(
            @NonNull Path executable, @NonNull SandboxProfile profile) {
        Path canonical = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(canonical)) {
            return;
        }
        Path compatibilityRoot = executableCompatibilityRoot(canonical, profile);
        if (compatibilityRoot == null) {
            compatibilityRoot = canonical.getParent();
        }
        if (compatibilityRoot == null) {
            return;
        }

        PointerByReference sid = new PointerByReference();
        String containerName = appContainerName(profile.workspaceRoot());
        int result =
                appContainerApi.DeriveAppContainerSidFromAppContainerName(
                        new WString(containerName), sid);
        if (result != 0 || sid.getValue() == null) {
            throw new IllegalStateException(
                    "DeriveAppContainerSidFromAppContainerName failed (HRESULT=" + result + ")");
        }
        try {
            tryGrantCompatibilityRoot(compatibilityRoot, sid.getValue(), FILE_GENERIC_READ_EXECUTE);
        } finally {
            api.FreeSid(sid.getValue());
        }
    }

    private static Path executableCompatibilityRoot(
            @NonNull Path executable, @NonNull SandboxProfile profile) {
        Path selected = null;
        for (Path root : profile.readExecuteRoots()) {
            if (executable.startsWith(root)
                    && (selected == null || root.getNameCount() < selected.getNameCount())) {
                selected = root;
            }
        }
        return selected;
    }

    synchronized void deprovision(@NonNull SandboxProfile profile) {
        releaseCreationMasks(profile.deniedPaths().stream().toList());
    }

    static @NonNull String appContainerName(@NonNull Path workspace) {
        String identity =
                workspace.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        byte[] digest;
        try {
            digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        ByteBuffer values = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN);
        return "VetoSandbox."
                + Long.toUnsignedString(values.getLong(), 16)
                + Long.toUnsignedString(values.getLong(), 16);
    }

    private void grantCompatibilityRoot(@NonNull Path root, @NonNull Pointer sid, int permissions) {
        Path canonical = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(canonical)) {
            return;
        }
        Path ancestor = canonical.getParent();
        while (ancestor != null) {
            grantAccess(ancestor, sid, DIRECTORY_TRAVERSE_AND_READ_ATTRIBUTES, 0);
            ancestor = ancestor.getParent();
        }
        grantAccess(canonical, sid, permissions, SUB_CONTAINERS_AND_OBJECTS_INHERIT);
    }

    private void tryGrantCompatibilityRoot(
            @NonNull Path root, @NonNull Pointer sid, int permissions) {
        try {
            grantCompatibilityRoot(root, sid, permissions);
        } catch (RuntimeException unavailable) {
            log.debug(
                    "Windows AppContainer compatibility ACL was not changed for {}: {}",
                    root,
                    unavailable.toString());
        }
    }

    private @NonNull List<@NonNull Path> acquireCreationMasks(@NonNull SandboxProfile profile) {
        List<Path> acquired = new ArrayList<>();
        for (Path deniedPath : profile.deniedPaths()) {
            Path denied = deniedPath.toAbsolutePath().normalize();
            if (!isWritableByProfile(denied, profile)) {
                continue;
            }
            SyntheticMask existing = syntheticMasks.get(denied);
            if (existing != null) {
                existing.references++;
                acquired.add(denied);
                continue;
            }
            if (Files.exists(denied, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                boolean directory = !isFileMask(denied);
                if (directory) {
                    Files.createDirectory(denied);
                } else {
                    Files.createFile(denied);
                }
                syntheticMasks.put(denied, new SyntheticMask(directory, 1));
                acquired.add(denied);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // A host process created the protected node first; provision() masks that real
                // node.
            } catch (java.io.IOException e) {
                releaseCreationMasks(acquired);
                throw new IllegalStateException(
                        "Cannot create protected Windows sandbox mask: " + denied, e);
            }
        }
        return List.copyOf(acquired);
    }

    private static boolean isWritableByProfile(
            @NonNull Path path, @NonNull SandboxProfile profile) {
        if (path.startsWith(profile.workspaceRoot())) {
            return true;
        }
        return profile.readWriteExecuteRoots().stream().anyMatch(path::startsWith);
    }

    private static boolean isFileMask(@NonNull Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.equals(".env")
                || name.equals("application.yml")
                || name.equals("application.yaml")
                || name.equals("application.properties");
    }

    private void releaseCreationMasks(@NonNull List<@NonNull Path> paths) {
        for (Path path : paths) {
            SyntheticMask mask = syntheticMasks.get(path);
            if (mask == null || --mask.references > 0) {
                continue;
            }
            syntheticMasks.remove(path);
            try {
                if (mask.directory) {
                    try (var children = Files.list(path)) {
                        if (children.findAny().isPresent()) {
                            continue;
                        }
                    }
                } else if (Files.size(path) != 0L) {
                    continue;
                }
                Files.deleteIfExists(path);
            } catch (java.io.IOException ignored) {
                // A host-side change wins: never delete a node that no longer matches the empty
                // mask.
            }
        }
    }

    private static final class SyntheticMask {
        private final boolean directory;
        private int references;

        private SyntheticMask(boolean directory, int references) {
            this.directory = directory;
            this.references = references;
        }
    }

    private void grantAccess(
            @NonNull Path workspace, @NonNull Pointer sid, int permissions, int inheritance) {
        changeAccess(workspace, sid, permissions, inheritance, GRANT_ACCESS, true);
    }

    private void denyAccess(
            @NonNull Path path, @NonNull Pointer sid, int permissions, int inheritance) {
        changeAccess(path, sid, permissions, inheritance, DENY_ACCESS, false);
    }

    private void changeAccess(
            @NonNull Path workspace,
            @NonNull Pointer sid,
            int permissions,
            int inheritance,
            int accessMode,
            boolean skipWhenAlreadyEffective) {
        PointerByReference oldAcl = new PointerByReference();
        PointerByReference securityDescriptor = new PointerByReference();
        PointerByReference newAcl = new PointerByReference();
        try {
            int getResult =
                    api.GetNamedSecurityInfoW(
                            new WString(workspace.toAbsolutePath().normalize().toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            oldAcl,
                            null,
                            securityDescriptor);
            if (getResult != 0) {
                throw new IllegalStateException(
                        "GetNamedSecurityInfoW failed (Win32 error=" + getResult + ")");
            }

            if (skipWhenAlreadyEffective) {
                Trustee effectiveTrustee = new Trustee();
                effectiveTrustee.pMultipleTrustee = null;
                effectiveTrustee.multipleTrusteeOperation = 0;
                effectiveTrustee.trusteeForm = TRUSTEE_IS_SID;
                effectiveTrustee.trusteeType = TRUSTEE_IS_UNKNOWN;
                effectiveTrustee.ptstrName = sid;
                effectiveTrustee.write();
                IntByReference effectiveRights = new IntByReference();
                int rightsResult =
                        api.GetEffectiveRightsFromAclW(
                                oldAcl.getValue(), effectiveTrustee, effectiveRights);
                if (rightsResult != 0) {
                    throw new IllegalStateException(
                            "GetEffectiveRightsFromAclW failed (Win32 error=" + rightsResult + ")");
                }
                if ((effectiveRights.getValue() & permissions) == permissions) {
                    return;
                }
            }

            ExplicitAccess access = explicitSidAccess(sid, permissions, accessMode, inheritance);

            int aclResult = api.SetEntriesInAclW(1, access, oldAcl.getValue(), newAcl);
            if (aclResult != 0) {
                throw new IllegalStateException(
                        "SetEntriesInAclW failed (Win32 error=" + aclResult + ")");
            }
            int setResult =
                    api.SetNamedSecurityInfoW(
                            new WString(workspace.toAbsolutePath().normalize().toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            newAcl.getValue(),
                            null);
            if (setResult != 0) {
                throw new IllegalStateException(
                        "SetNamedSecurityInfoW failed (Win32 error=" + setResult + ")");
            }
        } finally {
            localFree(newAcl.getValue());
            localFree(securityDescriptor.getValue());
        }
    }

    /**
     * Breaks inheritance at a protected node and removes every sandbox identity from its DACL while
     * retaining the host user's, SYSTEM's, and Administrators' entries. A deny ACE alone is not a
     * reliable mask for an AppContainer restricted-token access check when an inherited package
     * allow ACE remains on the same file.
     */
    private static void removeSandboxAccess(
            @NonNull Path path, @NonNull Set<@NonNull String> sandboxSidStrings) {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            throw new IllegalStateException(
                    "Windows ACL view is unavailable for protected path: " + path);
        }
        try {
            List<AclEntry> retained = new ArrayList<>(view.getAcl());
            retained.removeIf(entry -> sandboxSidStrings.contains(entry.principal().getName()));
            view.setAcl(retained);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Cannot mask protected Windows sandbox path: " + path, e);
        }
    }

    private @NonNull String sidString(@NonNull Pointer sid) {
        PointerByReference value = new PointerByReference();
        if (!api.ConvertSidToStringSidW(sid, value) || value.getValue() == null) {
            throw new IllegalStateException(
                    "ConvertSidToStringSidW failed (Win32 error="
                            + Kernel32.INSTANCE.GetLastError()
                            + ")");
        }
        try {
            return value.getValue().getWideString(0);
        } finally {
            localFree(value.getValue());
        }
    }

    private static void localFree(Pointer pointer) {
        if (pointer != null) {
            Kernel32.INSTANCE.LocalFree(pointer);
        }
    }

    static @NonNull ExplicitAccess explicitSidAccess(
            @NonNull Pointer sid, int permissions, int accessMode, int inheritance) {
        ExplicitAccess access = new ExplicitAccess();
        access.grfAccessPermissions = permissions;
        access.grfAccessMode = accessMode;
        access.grfInheritance = inheritance;
        access.trustee.pMultipleTrustee = null;
        access.trustee.multipleTrusteeOperation = 0;
        access.trustee.trusteeForm = TRUSTEE_IS_SID;
        access.trustee.trusteeType = TRUSTEE_IS_UNKNOWN;
        access.trustee.ptstrName = sid;
        access.write();
        return access;
    }

    @Structure.FieldOrder({
        "pMultipleTrustee",
        "multipleTrusteeOperation",
        "trusteeForm",
        "trusteeType",
        "ptstrName"
    })
    public static class Trustee extends Structure {
        public Pointer pMultipleTrustee;
        public int multipleTrusteeOperation;
        public int trusteeForm;
        public int trusteeType;
        public Pointer ptstrName;
    }

    @Structure.FieldOrder({"grfAccessPermissions", "grfAccessMode", "grfInheritance", "trustee"})
    public static class ExplicitAccess extends Structure {
        public int grfAccessPermissions;
        public int grfAccessMode;
        public int grfInheritance;
        public @NonNull Trustee trustee = new Trustee();
    }

    interface WindowsAclApi extends StdCallLibrary {
        Pointer FreeSid(Pointer sid);

        boolean ConvertStringSidToSidW(WString stringSid, PointerByReference sid);

        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference stringSid);

        int GetNamedSecurityInfoW(
                WString objectName,
                int objectType,
                int securityInfo,
                PointerByReference owner,
                PointerByReference group,
                PointerByReference dacl,
                PointerByReference sacl,
                PointerByReference securityDescriptor);

        int SetEntriesInAclW(
                int explicitEntries,
                ExplicitAccess explicitAccess,
                Pointer oldAcl,
                PointerByReference newAcl);

        int GetEffectiveRightsFromAclW(
                Pointer acl, Trustee trustee, IntByReference effectiveRights);

        int SetNamedSecurityInfoW(
                WString objectName,
                int objectType,
                int securityInfo,
                Pointer owner,
                Pointer group,
                Pointer dacl,
                Pointer sacl);
    }

    interface WindowsAppContainerApi extends StdCallLibrary {
        int CreateAppContainerProfile(
                WString appContainerName,
                WString displayName,
                WString description,
                Pointer capabilities,
                int capabilityCount,
                PointerByReference appContainerSid);

        int DeriveAppContainerSidFromAppContainerName(
                WString appContainerName, PointerByReference appContainerSid);
    }
}
