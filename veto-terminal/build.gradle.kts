plugins {
    application
    kotlin("jvm") version "2.0.21"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "top.focess"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "top.focess.veto.terminal.VetoTerminal"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dslf4j.internal.verbosity=WARN",
    )
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val jlineVersion: String by rootProject.extra
val slf4jVersion: String by rootProject.extra
val logbackVersion: String by rootProject.extra

dependencies {
    // JSpecify nullability annotations
    compileOnly("org.jspecify:jspecify:1.0.0")

    // veto-protocol (wire types + shared interaction protocol + palette + logging).
    // Deliberately NOT veto-core (no Spring).
    implementation(project(":veto-protocol"))

    // JLine 3 for terminal I/O, line editing, ANSI, Display
    implementation("org.jline:jline:$jlineVersion")

    // Mordant — rich terminal output (tables, spinners, panels, ANSI detection)
    implementation("com.github.ajalt.mordant:mordant:3.0.2")

    // Logging backend — the application owns this (veto-protocol depends on the SLF4J facade only).
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")
}

spotless {
    java {
        googleJavaFormat("1.19.2").aosp()
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
