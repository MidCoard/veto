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

    api("org.slf4j:slf4j-api:$slf4jVersion")
    api("ch.qos.logback:logback-classic:$logbackVersion")
    api("org.slf4j:jul-to-slf4j:$slf4jVersion")

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
