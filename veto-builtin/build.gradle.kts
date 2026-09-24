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
    compileOnly("biz.aQute.bnd:biz.aQute.bnd.annotation:7.0.0")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
    compileOnly("org.osgi:org.osgi.annotation.bundle:2.0.0")
    testCompileOnly("biz.aQute.bnd:biz.aQute.bnd.annotation:7.0.0")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
    testCompileOnly("org.osgi:org.osgi.annotation.bundle:2.0.0")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    api(project(":veto-api"))
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${rootProject.extra["jacksonVersion"]}")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${rootProject.extra["jacksonVersion"]}")
    api("org.jspecify:jspecify:1.0.0")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("net.bytebuddy:byte-buddy:1.17.8")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.17.8")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
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
}
