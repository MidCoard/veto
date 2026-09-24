import org.springframework.boot.gradle.tasks.run.BootRun
plugins {
    java
    id("com.diffplug.spotless") version "6.25.0"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.springframework.boot") version "3.5.16"
}
group = "top.focess"
version = "1.0.100"
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
repositories { mavenCentral() }
dependencies {
    implementation(project(":veto-core"))
    implementation(project(":veto-api"))
    implementation(project(":veto-plugin-runtime"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("biz.aQute.bnd:biz.aQute.bnd.annotation:7.0.0")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
    compileOnly("com.google.errorprone:error_prone_annotations:2.32.0")
    compileOnly("org.osgi:osgi.annotation:8.1.0")
    compileOnly("org.osgi:org.osgi.annotation.bundle:2.0.0")
    implementation(project(":veto-builtin"))
    runtimeOnly(project(":veto-llm-providers"))
    runtimeOnly(project(":veto-secret-protection"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testCompileOnly("biz.aQute.bnd:biz.aQute.bnd.annotation:7.0.0")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.8.6")
    testCompileOnly("org.osgi:osgi.annotation:8.1.0")
    testCompileOnly("org.osgi:org.osgi.annotation.bundle:2.0.0")
}
springBoot { mainClass.set("top.focess.veto.VetoApplication") }
tasks.named<BootRun>("bootRun") {
    workingDir(rootProject.projectDir)
    systemProperty("veto.observability.audit-log-path", rootProject.file("audit").absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
tasks.withType<Test> { useJUnitPlatform() }
