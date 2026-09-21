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
    api("org.jspecify:jspecify:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
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

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(":veto-plugin-fixture:pluginPackage")
    inputs.dir(project(":veto-plugin-fixture").layout.buildDirectory.dir("plugin/top.focess.fixture/0.1.0"))
        .withPropertyName("fixturePackage")
    systemProperty("fixture.package", project(":veto-plugin-fixture").layout.buildDirectory.dir("plugin/top.focess.fixture/0.1.0").get().asFile.absolutePath)
}
