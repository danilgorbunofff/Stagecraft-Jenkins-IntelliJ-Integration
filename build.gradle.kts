import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = "dev.stagecraft"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(libs.versions.idea)
        // The plugin auto-configures `test` to launch under the IDE harness (PathClassLoader +
        // coroutines agent), and the harness's JUnit5 environment initializer needs JUnit 4 on
        // the classpath or the executor aborts before the first test runs.
        testFramework(TestFrameworkType.Platform)
    }

    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// §9.1 JVM target trap: the toolchain may be 21, the bytecode may not.
// 2023.x - 2024.1 IDEs run on Java 17, so anything below since-build 242 must be Java 17.
//
// jvmTarget is set on the compile tasks rather than in the `kotlin` extension on purpose:
// jvmToolchain(21) resolves late and resets the extension-level target to 21, which then trips
// Gradle's "Inconsistent JVM-target compatibility" check against compileJava. Task-level
// configuration is applied last, so it is the value that survives.
kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "231"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnitPlatform()
    // The tests read the recorded Day-0 responses out of docs/fixtures.
    systemProperty("stagecraft.fixtures", layout.projectDirectory.dir("docs/fixtures").asFile.absolutePath)
}

/**
 * §11 Days 1-2 exit criterion, the headless half: print a real build's console to stdout.
 * No IDE, no window, no platform classpath.
 *
 *   ./gradlew consoleDump -PbuildUrl=http://localhost:18080/job/multibranch-demo/job/main/1/
 *
 * Credentials come from the environment so that no token is ever written into the repository:
 *   STAGECRAFT_USER, STAGECRAFT_TOKEN (or STAGECRAFT_PASSWORD)
 */
tasks.register<JavaExec>("consoleDump") {
    group = "stagecraft"
    description = "Print a Jenkins build's console to stdout, with no IDE involved."
    mainClass = "dev.stagecraft.cli.ConsoleDumpKt"
    classpath = sourceSets.main.get().runtimeClasspath
    (findProperty("buildUrl") as String?)?.let { args(it) }
    (findProperty("start") as String?)?.let { args(it) }
    standardInput = System.`in`
}
