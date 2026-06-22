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

val slf4jVersion: String by rootProject.extra
val logbackVersion: String by rootProject.extra

dependencies {
    // The wire-protocol types (IpcFrame, IpcClient, ClientTransport) are part of this module's
    // public API, so they are exposed to the client applications via `api`.
    api(project(":veto-contract"))

    compileOnly("org.jetbrains:annotations:24.1.0")

    // Shared logging bootstrap (Logback root level + JUL→SLF4J bridge). Application concern, not a
    // contract concern — lives here so both clients share one copy instead of two byte-identical ones.
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
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
