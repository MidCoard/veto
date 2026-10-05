package top.focess.veto.sandbox;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.security.HostPathInput;

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

    private final @NonNull Map<@NonNull Path, @NonNull AclSuppression> suppressedAcls =
            new HashMap<>();
    private final @NonNull
            Map<@NonNull SandboxProfile, @NonNull ArrayDeque<@NonNull List<@NonNull Path>>>
            projectedPaths = new HashMap<>();

    WindowsWorkspaceSecurity() {
        api = Native.load("advapi32", WindowsAclApi.class);
        appContainerApi = Native.load("userenv", WindowsAppContainerApi.class);
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
                    "ConvertStringSidToSidW(All Restricted Application Packages) failed (Win32"
                            + " error="
                            + Kernel32.INSTANCE.GetLastError()
                            + ")");
        }
        List<Path> acquiredMasks = List.of();
        List<@NonNull Path> projected = new ArrayList<>();
        List<@NonNull ByteBuffer> targets = List.of();
        boolean completed = false;
        try {
            acquiredMasks = acquireCreationMasks(profile);
            targets = projectionTargets(profile, Objects.requireNonNull(sid.getValue()));
            // Read/execute compatibility is projected lazily for the executable selected by this
            // invocation. Eagerly touching every PATH entry is both unnecessarily broad and very
            // expensive on developer machines with large toolchains.
            for (Path root : profile.readWriteExecuteRoots()) {
                Path canonical =
                        HostPathInput.canonicalForCreation(root, "sandbox compatibility root");
                if (overlapsDeniedRoot(canonical, profile)) continue;
                tryGrantCompatibilityRoot(canonical, sid.getValue(), FILE_ALL_ACCESS);
            }
            var nativeSid = Objects.requireNonNull(sid.getValue(), "AppContainer SID disappeared");
            if (suppressedAcls.keySet().stream().anyMatch(path -> path.startsWith(workspace))) {
                grantOwnerLocally(workspace, sidKey(nativeSid));
            } else {
                grantAccess(
                        workspace, nativeSid, FILE_ALL_ACCESS, SUB_CONTAINERS_AND_OBJECTS_INHERIT);
            }
            String appContainerSid = sidString(nativeSid);
            projectForeignRoots(profile, targets, projected);
            for (Path deniedPath : profile.protectedPaths()) {
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
            projectedPaths
                    .computeIfAbsent(profile, ignored -> new ArrayDeque<>())
                    .addLast(List.copyOf(projected));
            completed = true;
            return containerName;
        } finally {
            localFree(allRestrictedApplicationPackagesSid.getValue());
            localFree(allApplicationPackagesSid.getValue());
            api.FreeSid(sid.getValue());
            if (!completed) {
                try {
                    for (Path path : projected.reversed()) releaseProjection(path, targets);
                } finally {
                    releaseCreationMasks(acquiredMasks);
                }
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
        Path canonical = HostPathInput.canonicalForCreation(executable, "sandbox executable");
        if (profile.deniedPaths().stream().anyMatch(canonical::startsWith)) {
            throw new SecurityException("Executable is inside an inaccessible path");
        }
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
        if (overlapsDeniedRoot(compatibilityRoot, profile)) {
            throw new SecurityException("Executable compatibility root is inaccessible");
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
        try {
            var receipts = projectedPaths.get(profile);
            if (receipts != null && !receipts.isEmpty()) {
                var paths = receipts.removeFirst();
                var targets = projectionTargets(profile);
                for (Path path : paths.reversed()) releaseProjection(path, targets);
                if (receipts.isEmpty()) projectedPaths.remove(profile);
            }
        } finally {
            releaseCreationMasks(profile.deniedPaths().stream().toList());
        }
    }

    private boolean overlapsDeniedRoot(@NonNull Path root, @NonNull SandboxProfile profile) {
        return profile.protectedPaths().stream().anyMatch(root::startsWith)
                || profile.occupiedRoots().stream()
                        .anyMatch(denied -> root.startsWith(denied) || denied.startsWith(root))
                || projectedPaths.keySet().stream()
                        .filter(active -> active.workspaceRoot().equals(profile.workspaceRoot()))
                        .flatMap(active -> active.occupiedRoots().stream())
                        .anyMatch(denied -> root.startsWith(denied) || denied.startsWith(root));
    }

    /** Grants the rightful owner's identity without reinheriting suppressed package entries. */
    private void grantOwnerLocally(@NonNull Path workspace, @NonNull ByteBuffer owner) {
        try {
            Files.walkFileTree(
                    workspace,
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult preVisitDirectory(
                                @NonNull Path path, @NonNull BasicFileAttributes attributes) {
                            if (isReparsePoint(path)) return FileVisitResult.SKIP_SUBTREE;
                            grant(path, true);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NonNull FileVisitResult visitFile(
                                @NonNull Path path, @NonNull BasicFileAttributes attributes) {
                            if (!isReparsePoint(path)) grant(path, false);
                            return FileVisitResult.CONTINUE;
                        }

                        private void grant(@NonNull Path path, boolean directory) {
                            var suppression = suppressedAcls.get(path);
                            if (suppression != null && suppression.references.containsKey(owner))
                                return;
                            var acl = readDacl(path);
                            if (acl.entries.stream()
                                    .anyMatch(
                                            ace ->
                                                    Objects.equals(owner, allowedSid(ace))
                                                            && ByteBuffer.wrap(bytes(ace))
                                                                            .order(
                                                                                    ByteOrder
                                                                                            .LITTLE_ENDIAN)
                                                                            .getInt(4)
                                                                    == FILE_ALL_ACCESS)) return;
                            var data =
                                    ByteBuffer.allocate(8 + owner.remaining())
                                            .order(ByteOrder.LITTLE_ENDIAN);
                            data.put((byte) 0)
                                    .put((byte) (directory ? 3 : 0))
                                    .putShort((short) data.capacity())
                                    .putInt(FILE_ALL_ACCESS)
                                    .put(owner.duplicate());
                            var entry = ByteBuffer.wrap(data.array()).asReadOnlyBuffer();
                            var entries = new ArrayList<>(acl.entries);
                            entries.add(
                                    restorePosition(entries, Map.entry(entry, entries.size())),
                                    entry);
                            writeDacl(path, acl, entries);
                        }
                    });
        } catch (IOException failure) {
            throw new SecurityException("Cannot grant workspace owner access locally", failure);
        }
    }

    private @NonNull List<@NonNull ByteBuffer> projectionTargets(@NonNull SandboxProfile profile) {
        var sid = new PointerByReference();
        int result =
                appContainerApi.DeriveAppContainerSidFromAppContainerName(
                        new WString(appContainerName(profile.workspaceRoot())), sid);
        if (result != 0 || sid.getValue() == null)
            throw new IllegalStateException("Cannot derive retiring AppContainer SID: " + result);
        try {
            return projectionTargets(profile, Objects.requireNonNull(sid.getValue()));
        } finally {
            api.FreeSid(sid.getValue());
        }
    }

    private @NonNull List<@NonNull ByteBuffer> projectionTargets(
            @NonNull SandboxProfile profile, @NonNull Pointer containerSid) {
        List<@NonNull ByteBuffer> targets = new ArrayList<>();
        targets.add(sidKey(containerSid));
        targets.add(sidKey(ALL_APPLICATION_PACKAGES_SID));
        targets.add(sidKey(ALL_RESTRICTED_APPLICATION_PACKAGES_SID));
        if (profile.networkAllowed()) {
            for (String capability : WindowsAppContainerLauncher.NETWORK_CAPABILITY_SIDS) {
                targets.add(sidKey(capability));
            }
        }
        return List.copyOf(targets);
    }

    private @NonNull ByteBuffer sidKey(@NonNull String text) {
        var sid = new PointerByReference();
        if (!api.ConvertStringSidToSidW(new WString(text), sid) || sid.getValue() == null)
            throw new IllegalStateException("Cannot resolve package SID");
        try {
            return sidKey(Objects.requireNonNull(sid.getValue()));
        } finally {
            localFree(sid.getValue());
        }
    }

    private static @NonNull ByteBuffer sidKey(@NonNull Pointer sid) {
        int length = 8 + 4 * Byte.toUnsignedInt(sid.getByte(1));
        return ByteBuffer.wrap(sid.getByteArray(0, length)).asReadOnlyBuffer();
    }

    private void projectForeignRoots(
            @NonNull SandboxProfile profile,
            @NonNull List<@NonNull ByteBuffer> targets,
            @NonNull List<@NonNull Path> projected) {
        Set<Path> visited = new HashSet<>();
        for (Path root : profile.occupiedRoots()) {
            try {
                Files.walkFileTree(
                        root,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public @NonNull FileVisitResult preVisitDirectory(
                                    @NonNull Path directory,
                                    @NonNull BasicFileAttributes attributes) {
                                if (isReparsePoint(directory)) return FileVisitResult.SKIP_SUBTREE;
                                acquire(directory);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public @NonNull FileVisitResult visitFile(
                                    @NonNull Path file, @NonNull BasicFileAttributes attributes) {
                                if (!isReparsePoint(file)) acquire(file);
                                return FileVisitResult.CONTINUE;
                            }

                            private void acquire(@NonNull Path path) {
                                if (visited.add(path)) {
                                    acquireProjection(path, targets);
                                    projected.add(path);
                                }
                            }
                        });
            } catch (IOException exception) {
                throw new SecurityException(
                        "Foreign workspace ACL projection is incomplete", exception);
            }
        }
    }

    private static boolean isReparsePoint(@NonNull Path path) {
        int attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (attributes == -1) throw new SecurityException("Cannot inspect foreign workspace entry");
        return (attributes & 0x400)
                != 0; // FILE_ATTRIBUTE_REPARSE_POINT: never follow junctions/links.
    }

    private void acquireProjection(@NonNull Path path, @NonNull List<@NonNull ByteBuffer> targets) {
        var acl = readDacl(path);
        var existing = suppressedAcls.get(path);
        var state = existing == null ? new AclSuppression() : existing;
        var nextReferences = new HashMap<>(state.references);
        for (ByteBuffer target : targets) nextReferences.merge(target, 1, Integer::sum);
        var removed = new ArrayList<>(state.removed);
        List<@NonNull ByteBuffer> retained = new ArrayList<>();
        for (int index = 0; index < acl.entries.size(); index++) {
            ByteBuffer ace = acl.entries.get(index);
            ByteBuffer sid = allowedSid(ace);
            if (sid != null && targets.contains(sid)) removed.add(Map.entry(ace, index));
            else retained.add(ace);
        }
        if (retained.size() != acl.entries.size()) writeDacl(path, acl, retained);
        state.references = nextReferences;
        state.removed = removed;
        suppressedAcls.put(path, state);
    }

    private void releaseProjection(@NonNull Path path, @NonNull List<@NonNull ByteBuffer> targets) {
        var state = suppressedAcls.get(path);
        if (state == null) return;
        var nextReferences = new HashMap<>(state.references);
        Set<ByteBuffer> retired = new HashSet<>();
        for (ByteBuffer target : targets) {
            var count = nextReferences.get(target);
            if (count == null) throw new IllegalStateException("Unowned ACL projection reference");
            if (count > 1) nextReferences.put(target, count - 1);
            else {
                nextReferences.remove(target);
                retired.add(target);
            }
        }
        var restoring =
                state.removed.stream()
                        .filter(
                                entry -> {
                                    var allowed = allowedSid(entry.getKey());
                                    return allowed != null && retired.contains(allowed);
                                })
                        .sorted(Comparator.comparingInt(Entry::getValue))
                        .toList();
        if (!restoring.isEmpty() && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (isReparsePoint(path)) throw new SecurityException("Projected entry became a link");
            var acl = readDacl(path);
            var entries = new ArrayList<>(acl.entries);
            for (var entry : restoring)
                entries.add(restorePosition(entries, entry), entry.getKey());
            writeDacl(path, acl, entries);
        }
        state.references = nextReferences;
        state.removed.removeAll(restoring);
        if (state.references.isEmpty()) suppressedAcls.remove(path);
    }

    private static int restorePosition(
            @NonNull List<@NonNull ByteBuffer> entries,
            @NonNull Entry<@NonNull ByteBuffer, @NonNull Integer> restoring) {
        boolean inherited = (restoring.getKey().get(1) & 0x10) != 0;
        int minimum = 0;
        int maximum = entries.size();
        for (int index = 0; index < entries.size(); index++) {
            ByteBuffer ace = entries.get(index);
            if ((ace.get(1) & 0x10) == 0) {
                if (inherited || ace.get(0) == 1) minimum = index + 1;
            } else if (!inherited) {
                maximum = index;
                break;
            }
        }
        return Math.max(minimum, Math.min(restoring.getValue(), maximum));
    }

    private static ByteBuffer allowedSid(@NonNull ByteBuffer ace) {
        int type = Byte.toUnsignedInt(ace.get(0));
        if (type == 5 || type == 9 || type == 11)
            throw new SecurityException("Unsupported foreign workspace ALLOW ACE");
        if (type != 0) return null;
        if (ace.remaining() < 16) throw new SecurityException("Malformed ALLOW ACE");
        int end = 16 + 4 * Byte.toUnsignedInt(ace.get(9));
        if (end > ace.remaining()) throw new SecurityException("Malformed ALLOW SID");
        return ByteBuffer.wrap(Arrays.copyOfRange(bytes(ace), 8, end)).asReadOnlyBuffer();
    }

    private @NonNull NativeDacl readDacl(@NonNull Path path) {
        var acl = new PointerByReference();
        var descriptor = new PointerByReference();
        try {
            int result =
                    api.GetNamedSecurityInfoW(
                            new WString(path.toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            acl,
                            null,
                            descriptor);
            if (result != 0)
                throw new SecurityException("Cannot read foreign workspace ACL: " + result);
            var pointer = acl.getValue();
            if (pointer == null)
                throw new SecurityException("Foreign workspace has an unrestricted DACL");
            int length = Short.toUnsignedInt(pointer.getShort(2));
            if (length < 8) throw new SecurityException("Malformed foreign ACL header");
            var data = pointer.getByteArray(0, length);
            var view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int count = Short.toUnsignedInt(view.getShort(4));
            List<@NonNull ByteBuffer> entries = new ArrayList<>();
            int offset = 8;
            for (int index = 0; index < count; index++) {
                if (offset + 4 > data.length)
                    throw new SecurityException("Malformed foreign ACE header");
                int size = Short.toUnsignedInt(view.getShort(offset + 2));
                if (size < 4 || offset + size > data.length)
                    throw new SecurityException("Malformed foreign ACL");
                entries.add(
                        ByteBuffer.wrap(Arrays.copyOfRange(data, offset, offset + size))
                                .asReadOnlyBuffer());
                offset += size;
            }
            var security = Objects.requireNonNull(descriptor.getValue());
            return new NativeDacl(Arrays.copyOf(data, 8), entries, security.getShort(2));
        } finally {
            localFree(descriptor.getValue());
        }
    }

    private void writeDacl(
            @NonNull Path path,
            @NonNull NativeDacl original,
            @NonNull List<@NonNull ByteBuffer> entries) {
        // SetFileSecurity updates only this node. Windows clears its historical AUTO_INHERITED
        // marker, but retains the ACE inheritance flags and protection. SetNamedSecurityInfo's
        // descendant propagation could reintroduce package allows into another live projection.
        int length = 8 + entries.stream().mapToInt(ByteBuffer::remaining).sum();
        if (length > 65535 || entries.size() > 65535)
            throw new SecurityException("Foreign ACL exceeds native limits");
        var data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        data.put(original.header).putShort(2, (short) length).putShort(4, (short) entries.size());
        for (ByteBuffer entry : entries) data.put(entry.duplicate());
        try (var acl = new Memory(length);
                var descriptor = new Memory(64)) {
            acl.write(0, data.array(), 0, length);
            if (!api.InitializeSecurityDescriptor(descriptor, 1)
                    || !api.SetSecurityDescriptorDacl(
                            descriptor, true, acl, (original.control & 8) != 0)
                    || !api.SetSecurityDescriptorControl(
                            descriptor, (short) 0x1500, (short) (original.control & 0x1500))
                    || !api.SetFileSecurityW(
                            new WString(path.toString()), DACL_SECURITY_INFORMATION, descriptor))
                throw new SecurityException(
                        "Cannot write foreign workspace ACL: " + Kernel32.INSTANCE.GetLastError());
        }
    }

    private static byte @NonNull [] bytes(@NonNull ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        return data;
    }

    private static final class NativeDacl {
        private final byte @NonNull [] header;
        private final @NonNull List<@NonNull ByteBuffer> entries;
        private final short control;

        private NativeDacl(
                byte @NonNull [] header,
                @NonNull List<@NonNull ByteBuffer> entries,
                short control) {
            this.header = header;
            this.entries = entries;
            this.control = control;
        }
    }

    private static final class AclSuppression {
        private @NonNull Map<@NonNull ByteBuffer, @NonNull Integer> references = new HashMap<>();
        private @NonNull List<@NonNull Entry<@NonNull ByteBuffer, @NonNull Integer>> removed =
                new ArrayList<>();
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
            } catch (FileAlreadyExistsException raced) {
                // A host process created the protected node first; provision() masks that real
                // node.
            } catch (IOException e) {
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
            } catch (IOException ignored) {
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
        } catch (IOException e) {
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

    /** Win32 {@code TRUSTEE_W}: the SID-form trustee an ACL entry applies to. */
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

    /** Win32 {@code EXPLICIT_ACCESS_W}: one grant/deny entry merged into the DACL. */
    @Structure.FieldOrder({"grfAccessPermissions", "grfAccessMode", "grfInheritance", "trustee"})
    public static class ExplicitAccess extends Structure {
        public int grfAccessPermissions;
        public int grfAccessMode;
        public int grfInheritance;
        public @NonNull Trustee trustee = new Trustee();
    }

    interface WindowsAclApi extends StdCallLibrary {
        boolean InitializeSecurityDescriptor(Pointer descriptor, int revision);

        boolean SetSecurityDescriptorDacl(
                Pointer descriptor, boolean present, Pointer acl, boolean defaulted);

        boolean SetSecurityDescriptorControl(Pointer descriptor, short interest, short control);

        boolean SetFileSecurityW(WString path, int information, Pointer descriptor);

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
