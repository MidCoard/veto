import java.io.File
plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

group = "top.focess"
version = "1.0.95"

// Centralized dependency versions shared across all subprojects
extra["jacksonVersion"] = "2.18.0"
extra["jeromqVersion"] = "0.6.0"
extra["slf4jVersion"] = "2.0.13"
extra["logbackVersion"] = "1.5.13"
extra["jlineVersion"] = "3.26.1"

// ── Local release packaging ──────────────────────────────────────────────────
// Assembles ONE versioned, self-contained bundle under release/veto-<version>/
// from the three modules: the executable Spring Boot core jar, the terminal
// distribution zip (application-plugin dist with bin/ launchers), launcher
// scripts for the core jar, and VERSION/README. Idempotent: wipes and
// rebuilds its own version directory, never touches other versions. release/
// is a local artifact (gitignored).
val localRelease by tasks.registering {
    group = "release"
    description = "Assembles the versioned local release bundle under release/."
    dependsOn(":veto-core:bootJar", ":veto-terminal:distZip", ":veto-protocol:jar")
    val versionStr = project.version.toString()
    val outDir = layout.projectDirectory.dir("release/veto-$versionStr")
    doLast {
        val out = outDir.asFile
        out.deleteRecursively()
        val coreDir = File(out, "core").apply { mkdirs() }
        val terminalDir = File(out, "terminal").apply { mkdirs() }
        val bootJar =
                project(":veto-core")
                        .layout
                        .buildDirectory
                        .file("libs/veto-core-$versionStr.jar")
                        .get()
                        .asFile
        bootJar.copyTo(File(coreDir, "veto-core.jar"))
        val distZip =
                project(":veto-terminal")
                        .layout
                        .buildDirectory
                        .file("distributions/veto-terminal-$versionStr.zip")
                        .get()
                        .asFile
        distZip.copyTo(File(terminalDir, distZip.name))
        val dollar = "$"
        File(out, "start-core.bat")
                .writeText(
                        "@echo off\r\n" +
                                "rem Starts the veto-core server (visible console).\r\n" +
                                "java --enable-native-access=ALL-UNNAMED -jar \"%~dp0core\\veto-core.jar\" %*\r\n")
        File(out, "start-core.sh")
                .writeText(
                        "#!/bin/sh\n" +
                                "# Starts the veto-core server.\n" +
                                "exec java --enable-native-access=ALL-UNNAMED -jar \"" +
                                dollar +
                                "(dirname \"" +
                                dollar +
                                "0\")/core/veto-core.jar\" \"" +
                                dollar +
                                "@\"\n")
        File(out, "VERSION").writeText("component=veto\nversion=$versionStr\n")
        File(out, "README.md")
                .writeText(
                        "# Veto $versionStr — local release\n\n" +
                                "- **core**: `core/veto-core.jar` — the Spring Boot server. Start with\n" +
                                "  `start-core.bat` (Windows) or `start-core.sh` (Unix), or\n" +
                                "  `java --enable-native-access=ALL-UNNAMED -jar core/veto-core.jar`.\n" +
                                "  Serves the REST + WebSocket API on port 8443.\n" +
                                "- **terminal**: `terminal/veto-terminal-$versionStr.zip` — the terminal\n" +
                                "  client distribution. Unzip and run `bin/veto-terminal(.bat)`; it\n" +
                                "  connects to the core over IPC.\n")
        println("Local release assembled: ${out.absolutePath}")
    }
}
