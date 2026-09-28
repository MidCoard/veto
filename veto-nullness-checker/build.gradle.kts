plugins {
    `java-library`
    id("com.diffplug.spotless") version "6.25.0"
}

group = "top.focess"
version = "1.0.100"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.checkerframework:checker:4.1.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
}

spotless {
    java {
        googleJavaFormat("1.28.0").aosp()
        target("src/main/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
