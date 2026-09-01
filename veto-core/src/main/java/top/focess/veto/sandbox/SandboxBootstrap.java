package top.focess.veto.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDocs;

/** Trusted pre-target bootstrap for the Windows and Linux OS sandbox backends. */
public final class SandboxBootstrap {

    public static final @NonNull String MARKER = "--veto-sandbox-bootstrap";
    static final @NonNull String LINUX_CHILD_MARKER = "--veto-linux-sandbox-child";
    private static final int SYNCHRONIZE = 0x00100000;
    private static final int EVENT_MODIFY_STATE = 0x0002;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int INFINITE = -1;

    private SandboxBootstrap() {}

    static boolean isAppContainerLaunchAvailable() {
        return WindowsAppContainerLauncher.isAvailable();
    }

    /** Entry point used when Veto is running from a regular JVM classpath. */
    @SuppressWarnings("UnnecessaryModifier") // Invoked by classpath and Spring Boot launchers.
    public static void main(@NonNull String @NonNull [] args) {
        System.exit(run(args));
    }

    public static boolean isInvocation(@NonNull String @NonNull [] args) {
        return args.length > 0 && (MARKER.equals(args[0]) || LINUX_CHILD_MARKER.equals(args[0]));
    }

    /** Run the bootstrap invocation and return the target's exit code. */
    public static int run(@NonNull String @NonNull [] args) {
        if (args.length >= 3 && LINUX_CHILD_MARKER.equals(args[0]) && "--".equals(args[1])) {
            return LinuxSandboxBootstrap.run(Arrays.asList(args).subList(2, args.length));
        }
        if (args.length < 8 || !MARKER.equals(args[0]) || !"--".equals(args[6])) {
            System.err.println("Invalid Veto sandbox bootstrap invocation");
            return 125;
        }
        String gateName = args[1];
        String readyName = args[2];
        String appContainerName = args[3];
        String desktopName = args[4];
        boolean networkAllowed = "allow-network".equals(args[5]);
        List<String> target = Arrays.asList(args).subList(7, args.length);
        WinNT.HANDLE desktop;
        try {
            // Open before the parent attaches this bootstrap to the Job. The Job deliberately
            // forbids
            // creating or opening additional desktops after the readiness signal.
            desktop = WindowsAppContainerLauncher.openPrivateDesktop(desktopName);
        } catch (RuntimeException failure) {
            System.err.println(failure.getMessage());
            return 125;
        }
        try {
            if (!awaitGate(gateName, readyName)) {
                return 125;
            }
            return WindowsAppContainerLauncher.run(
                    target, appContainerName, desktopName, networkAllowed);
        } finally {
            WindowsAppContainerLauncher.closePrivateDesktop(desktop);
        }
    }

    static @NonNull String absoluteClassPath(@NonNull String classPath) {
        return String.join(
                File.pathSeparator,
                Arrays.stream(classPath.split(Pattern.quote(File.pathSeparator), -1))
                        .map(SandboxBootstrap::absoluteClassPathEntry)
                        .toList());
    }

    /**
     * Command prefix that launches this bootstrap from either a native image or the current JVM.
     */
    static @NonNull List<@NonNull String> processInvocation() {
        List<String> command = new ArrayList<>();
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            command.add(
                    ProcessHandle.current()
                            .info()
                            .command()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Native Veto executable path is unavailable")));
            return List.copyOf(command);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javaExecutable =
                Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            throw new IllegalStateException("Java classpath is unavailable for sandbox bootstrap");
        }
        classPath = absoluteClassPath(classPath);
        command.add(javaExecutable.toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Xms8m");
        command.add("-Xmx64m");
        command.add("-XX:MaxMetaspaceSize=64m");
        command.add("-XX:ReservedCodeCacheSize=32m");
        command.add("-XX:+UseSerialGC");
        if (!classPath.contains(File.pathSeparator) && classPath.endsWith(".jar")) {
            command.add("-Dloader.main=" + SandboxBootstrap.class.getName());
            command.add("-cp");
            command.add(classPath);
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(SandboxBootstrap.class.getName());
        }
        return List.copyOf(command);
    }

    private static @NonNull String absoluteClassPathEntry(@NonNull String entry) {
        if (entry.endsWith("/*") || entry.endsWith("\\*")) {
            String directory = entry.substring(0, entry.length() - 1);
            return Path.of(directory).toAbsolutePath().normalize() + File.separator + "*";
        }
        return Path.of(entry).toAbsolutePath().normalize().toString();
    }

    static @NonNull String windowsCommandLine(@NonNull List<@NonNull String> arguments) {
        StringBuilder result = new StringBuilder();
        for (String argument : arguments) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            appendWindowsArgument(result, argument);
        }
        return result.toString();
    }

    private static void appendWindowsArgument(
            @NonNull StringBuilder result, @NonNull String argument) {
        boolean quote =
                argument.isEmpty()
                        || argument.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '"');
        if (!quote) {
            result.append(argument);
            return;
        }
        result.append('"');
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char value = argument.charAt(i);
            if (value == '\\') {
                backslashes++;
            } else if (value == '"') {
                result.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                result.append("\\".repeat(backslashes)).append(value);
                backslashes = 0;
            }
        }
        result.append("\\".repeat(backslashes * 2)).append('"');
    }

    private static boolean awaitGate(@NonNull String gateName, @NonNull String readyName) {
        BootstrapKernel32 kernel =
                Native.load("kernel32", ToolDocs.nonNullClass(BootstrapKernel32.class));
        WinNT.HANDLE gate = kernel.OpenEventW(SYNCHRONIZE, false, new WString(gateName));
        if (gate == null || gate.getPointer() == null) {
            System.err.println(
                    "Sandbox launch gate is unavailable (Win32 error="
                            + Kernel32.INSTANCE.GetLastError()
                            + ")");
            return false;
        }
        WinNT.HANDLE ready = kernel.OpenEventW(EVENT_MODIFY_STATE, false, new WString(readyName));
        if (ready == null || ready.getPointer() == null) {
            Kernel32.INSTANCE.CloseHandle(gate);
            System.err.println(
                    "Sandbox readiness gate is unavailable (Win32 error="
                            + Kernel32.INSTANCE.GetLastError()
                            + ")");
            return false;
        }
        try {
            if (!kernel.SetEvent(ready)) {
                System.err.println(
                        "Sandbox readiness signal failed (Win32 error="
                                + Kernel32.INSTANCE.GetLastError()
                                + ")");
                return false;
            }
            int wait = kernel.WaitForSingleObject(gate, INFINITE);
            if (wait != WAIT_OBJECT_0) {
                System.err.println("Sandbox launch gate failed (wait=" + wait + ")");
                return false;
            }
            return true;
        } finally {
            Kernel32.INSTANCE.CloseHandle(ready);
            Kernel32.INSTANCE.CloseHandle(gate);
        }
    }

    interface BootstrapKernel32 extends Library {
        WinNT.HANDLE OpenEventW(int desiredAccess, boolean inheritHandle, WString name);

        int WaitForSingleObject(WinNT.HANDLE handle, int milliseconds);

        boolean SetEvent(WinNT.HANDLE handle);
    }
}
