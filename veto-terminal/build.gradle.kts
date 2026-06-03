plugins {
    application
    id("com.diffplug.spotless") version "6.25.0"
}

group = "top.focess"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "top.focess.veto.terminal.VetoTerminal"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // ONLY veto-contract — deliberately NOT veto-core (no Spring)
    implementation(project(":veto-contract"))

    // JLine 3 for terminal I/O, line editing, ANSI, Display
    implementation("org.jline:jline:3.26.1")

    // Jackson — declared explicitly, NOT inherited from Spring Boot BOM
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
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
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}
