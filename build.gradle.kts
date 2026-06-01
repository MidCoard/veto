plugins {
    java
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.google.protobuf") version "0.9.4"
    id("com.diffplug.spotless") version "6.25.0"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "top.focess"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // gRPC
    implementation("io.grpc:grpc-netty-shaded:1.62.2")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java:3.25.3")

    // JSON Processing (from Spring Boot BOM)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Encryption
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // WebSocket Client
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // Official LLM SDKs
    implementation("com.openai:openai-java:4.37.0")
    implementation("com.anthropic:anthropic-java:2.35.0")
    implementation("com.google.genai:google-genai:1.53.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
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

springBoot {
    mainClass = "top.focess.veto.VetoApplication"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-encoding")
    options.compilerArgs.add("UTF-8")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
