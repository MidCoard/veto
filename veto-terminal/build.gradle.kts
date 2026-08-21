plugins {
    application
    kotlin("jvm") version "2.3.0"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "top.focess"
version = "1.0.99"

application {
    mainClass = "top.focess.veto.terminal.VetoTerminal"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dslf4j.internal.verbosity=WARN",
        "--enable-native-access=ALL-UNNAMED",
    )
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

repositories {
    mavenCentral()
}

val jlineVersion: String by rootProject.extra
val slf4jVersion: String by rootProject.extra
val logbackVersion: String by rootProject.extra

dependencies {
    // JSpecify nullability contracts are part of normal compilation and reflection metadata.
    implementation("org.jspecify:jspecify:1.0.0")

    // veto-protocol (wire types + shared interaction protocol + palette + logging).
    // Deliberately NOT veto-core (no Spring).
    implementation(project(":veto-protocol"))

    // JLine 3 for terminal I/O, line editing, ANSI, Display
    implementation("org.jline:jline:$jlineVersion")

    // Mordant 鈥?rich terminal output (tables, spinners, panels, ANSI detection)
    implementation("com.github.ajalt.mordant:mordant:3.0.2")

    // Logging backend 鈥?the application owns this (veto-protocol depends on the SLF4J facade only).
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")
}

// 鈹€鈹€ Build-time version source 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// Generates top.focess.veto.terminal.VetoVersion (COMPONENT + VERSION) from project.version so the
// runtime can report its build version (sent to the backend in the IPC Hello handshake). Spotless
// targets src/** only, so generated sources under build/ are exempt from formatting.
val generatedVersionDir = layout.buildDirectory.dir("generated/sources/vetoVersion/java")
val generateVersion by tasks.registering {
    val pkg = "top.focess.veto.terminal"
    val component = project.name
    val versionStr = project.version.toString()
    inputs.property("component", component)
    inputs.property("version", versionStr)
    outputs.dir(generatedVersionDir)
    doLast {
        val pkgDir = generatedVersionDir.get().asFile.resolve(pkg.replace('.', '/'))
        pkgDir.mkdirs()
        val sb = StringBuilder()
        sb.append("package ").append(pkg).append(";\n\n")
        sb.append("import org.jspecify.annotations.NonNull;\n")
        sb.append("import top.focess.veto.contract.Version;\n\n")
        sb.append("/** Build-time generated version descriptor. Do not edit by hand. */\n")
        sb.append("public final class VetoVersion {\n")
        sb.append("    private VetoVersion() {}\n\n")
        sb.append("    public static final @NonNull String COMPONENT = \"")
                .append(component)
                .append("\";\n\n")
        sb.append("    public static final @NonNull Version VERSION = Version.parse(\"")
                .append(versionStr)
                .append("\");\n")
        sb.append("}\n")
        pkgDir.resolve("VetoVersion.java").writeText(sb.toString())
    }
}
sourceSets {
    main {
        java.srcDir(generatedVersionDir)
    }
}
tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateVersion)
}
// The Kotlin plugin (applied for the terminal) also scans the main java source set, so its
// compile task must depend on the generator too - otherwise Gradle flags an implicit dependency.
tasks.named("compileKotlin") {
    dependsOn(generateVersion)
}

spotless {
    java {
        googleJavaFormat("1.28.0").aosp()
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-encoding")
    options.compilerArgs.add("UTF-8")
}

// src/test currently contains HintTest, a manual live-backend harness with main(), not a JUnit
// test. Keep compiling it (including NullAway) without treating zero discovered tests as failure.
tasks.named<Test>("test") {
    failOnNoDiscoveredTests = false
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("runDebug") {
    group = "application"
    description = "Runs the terminal in debug mode with fine-grained logging to veto_debug.log"
    mainClass = "top.focess.veto.terminal.VetoTerminal"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    args = listOf("--debug")
}
