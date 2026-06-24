plugins {
    `java-library`
    id("com.diffplug.spotless") version "6.25.0"
}

group = "top.focess"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val jacksonVersion: String by rootProject.extra
val jeromqVersion: String by rootProject.extra
val slf4jVersion: String by rootProject.extra
val logbackVersion: String by rootProject.extra

dependencies {
    compileOnly("org.jetbrains:annotations:24.1.0")
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    api("org.zeromq:jeromq:$jeromqVersion")

    // SLF4J facade only — the protocol module must not mandate a logging implementation. Each
    // application module (terminal, core) brings its own backend (Logback).
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // Logback + JUL→SLF4J bridge: compileOnly for the merged-in client.core classes (Logging
    // bootstraps Logback). CompileOnly so no consumer is forced into a backend — both the terminal
    // and core declare their own Logback.
    compileOnly("ch.qos.logback:logback-classic:$logbackVersion")
    compileOnly("org.slf4j:jul-to-slf4j:$slf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Logback + JUL→SLF4J at test runtime so this module's own tests have a backend.
    testRuntimeOnly("ch.qos.logback:logback-classic:$logbackVersion")
    testRuntimeOnly("org.slf4j:jul-to-slf4j:$slf4jVersion")
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
}

tasks.withType<Test> {
    useJUnitPlatform()
}
