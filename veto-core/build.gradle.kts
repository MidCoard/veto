plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.google.protobuf") version "0.9.4"
    id("com.diffplug.spotless") version "6.25.0"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "top.focess"
version = "1.0.25"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":veto-protocol"))

    // JSpecify nullability annotations
    compileOnly("org.jspecify:jspecify:1.0.0")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // gRPC
    implementation("io.grpc:grpc-netty-shaded:1.75.0")
    implementation("io.grpc:grpc-protobuf:1.75.0")
    implementation("io.grpc:grpc-stub:1.75.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java:4.28.2")

    // JSON Processing (from Spring Boot BOM)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // Focess Command API
    implementation("top.focess:focess-command:2.2.0")
    implementation("top.focess:focess-scheduler:2.0.0")

    // ZMQ transport (shared with veto-protocol)
    implementation("org.zeromq:jeromq:0.6.0")

    // Encryption
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // Keystead credential vault (Maven Central release)
    implementation("top.focess:keystead-core:0.2.0")

    // JNA (Java Native Access) for kernel-level sandbox substrate
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // WebSocket Client
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // Official LLM SDKs
    implementation("com.openai:openai-java:4.38.0")
    implementation("com.anthropic:anthropic-java:2.35.0")
    implementation("com.google.genai:google-genai:1.56.0")

    // Database
    implementation("io.projectreactor:reactor-core")
    runtimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.2"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.75.0"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}

// ── Build-time version source ──────────────────────────────────────────
// Generates top.focess.veto.VetoVersion (COMPONENT + VERSION) from project.version so the runtime
// can report its build version (e.g. in /version and the IPC handshake). Spotless targets src/**
// only, so generated sources under build/ are exempt from formatting.
val generatedVersionDir = layout.buildDirectory.dir("generated/sources/vetoVersion/java")
val generateVersion by tasks.registering {
    val pkg = "top.focess.veto"
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
        sb.append("import ").append(pkg).append(".contract.Version;\n\n")
        sb.append("/** Build-time generated version descriptor. Do not edit by hand. */\n")
        sb.append("public final class VetoVersion {\n")
        sb.append("    private VetoVersion() {}\n\n")
        sb.append("    public static final String COMPONENT = \"").append(component).append("\";\n\n")
        sb.append("    public static final Version VERSION = Version.parse(\"")
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

spotless {
    java {
        googleJavaFormat("1.28.0").aosp()
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

springBoot {
    mainClass = "top.focess.veto.VetoApplication"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-encoding")
    options.compilerArgs.add("UTF-8")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Byte Buddy (Mockito) does not officially support Java 25 yet; allow experimental support.
    systemProperty("net.bytebuddy.experimental", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
