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

dependencies {
    compileOnly("org.jetbrains:annotations:24.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("org.zeromq:jeromq:0.6.0")

    api("org.slf4j:slf4j-api:2.0.13")
    api("ch.qos.logback:logback-classic:1.5.6")
    api("org.slf4j:jul-to-slf4j:2.0.13")

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
