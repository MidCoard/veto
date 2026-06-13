plugins {
    application
    kotlin("jvm") version "2.0.21"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "top.focess"
version = "1.0.0-SNAPSHOT"

application {
    mainClass.set("top.focess.veto.tui.VetoTui")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":veto-contract"))

    // JLine 3 core and dependencies
    implementation("org.jline:jline:3.26.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("org.zeromq:jeromq:0.6.0")
}

spotless {
    java {
        googleJavaFormat("1.19.2").aosp()
        target("src/main/java/**/*.java")
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

tasks.register<JavaExec>("runDebug") {
    group = "application"
    description = "Runs the TUI client in debug mode with logging redirected to veto_tui_debug.log"
    mainClass.set("top.focess.veto.tui.VetoTui")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    args = listOf("--debug")
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}
