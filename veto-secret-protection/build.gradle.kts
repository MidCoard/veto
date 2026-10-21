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
    api(project(":veto-api"))
    api("org.jspecify:jspecify:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// Offline model tooling may use the real host compiler/transport. It is not part
// of the installed plugin or its production dependency graph.
val sft by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[sft.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
    add(sft.implementationConfigurationName, project(":veto-core"))
    add(sft.implementationConfigurationName, platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    add(sft.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind")
    add(sft.compileOnlyConfigurationName, "org.springframework:spring-context")
    add(sft.compileOnlyConfigurationName, "org.springframework.boot:spring-boot")
}

tasks.register<JavaExec>("secretSft") {
    description = "Export synthetic SFT prompts or evaluate a secret GGUF with production contracts."
    classpath = sft.runtimeClasspath
    mainClass.set("top.focess.veto.secret.training.SecretSftTool")
    workingDir(rootProject.projectDir)
    val scratch = rootProject.file("work/tmp/secret-sft")
    systemProperty("java.io.tmpdir", scratch.absolutePath)
    systemProperty("jdk.net.unixdomain.tmpdir", scratch.absolutePath)
    systemProperty("veto.observability.audit-log-path", File(scratch, "audit").absolutePath)
    doFirst {
        scratch.mkdirs()
        args(project.providers.gradleProperty("secretSftInput").get(), project.providers.gradleProperty("secretSftOutput").get())
        if (project.hasProperty("secretSftModel")) {
            args(project.providers.gradleProperty("secretSftModel").get(), project.providers.gradleProperty("secretSftExecutable").get())
        }
    }
}
tasks.check { dependsOn(sft.classesTaskName) }

spotless {
    java {
        googleJavaFormat("1.28.0").aosp()
        target("src/main/java/**/*.java", "src/test/java/**/*.java", "src/sft/java/**/*.java")
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

// Offline tooling only; never included in runtimeClasspath or a plugin package.
val modelTraining by configurations.creating
dependencies { add(modelTraining.name, project(":veto-training")) }
tasks.register<JavaExec>("trainModel") {
    group = "model preparation"
    description = "Train and export the veto-secret-protection model through the shared Java API."
    classpath = modelTraining
    mainClass.set("top.focess.veto.training.TrainingCli")
    workingDir(rootProject.projectDir)
    doFirst {
        args(rootProject.projectDir.absolutePath,
            providers.gradleProperty("trainingConfig").getOrElse("veto-secret-protection/training/secret.properties"))
    }
}
