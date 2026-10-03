import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = "dev.stagecraft"
version = "1.0.0"

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

// §9.1 compatibility floor: 2024.3 (since-build 243).
//
// The floor is set by the Kotlin standard library, not by the JVM. A plugin never bundles its own
// stdlib - it runs on the one the IDE ships - and Kotlin 2.4 can only emit code for a stdlib of 2.0
// or newer ("API version 1.9 is no longer supported"). 2024.3 is the first IDE that ships Kotlin
// 2.0 (2.0.21; 2024.2 ships 1.9.24, as `verifyPluginProjectConfiguration` reports), so it is the
// oldest IDE this build can honestly claim. Below 2023.3, every enum's static initializer calls
// `kotlin.enums.EnumEntriesKt` (Kotlin 1.9+) and the plugin dies with NoClassDefFoundError.
//
// apiVersion pins the stdlib surface to what 2024.3 ships, so the compiler rejects any newer call
// instead of leaving it to fail at runtime on the oldest supported IDE. 2024.3 also runs on Java 21,
// so the old Java 17 bytecode workaround is gone.
kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_0
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.stagecraft.jenkins"
        // §8.2: the listing title is the plugin name, so it carries the search words. The charter's
        // `•` is not allowed in a plugin name (verifyPluginStructure: letters, digits, spaces and
        // .,+_-/:()#'&[]| only), so the separator is a hyphen.
        name = "Stagecraft - Jenkins Build & Log Viewer"
        // §8.2's first and second lines, verbatim. Marketplace requires the description to open
        // with at least 40 Latin characters, which the first line does.
        description = provider {
            "<p>Your Jenkins build failed. See which stage broke, its log, and click the failing " +
                "line straight into your editor \u2014 without leaving the IDE.</p>" +
                "<p>Stagecraft finds your Jenkins server and job from your git remote. It never " +
                "lists the whole server, so it works on installations with folders, multibranch " +
                "jobs and thousands of jobs.</p>"
        }
        changeNotes = provider { "1.0.0 - first release: branch build list, stage view, bounded log with live tail, test results and jump-to-source, Jenkinsfile lint." }

        // §8.2/§8.3: the listing's vendor and its permanent product code. The code is the
        // candidate from the charter; it must be registered with JetBrains before a paid listing
        // goes live, and it is permanent once it is.
        vendor {
            name = "Stagecraft"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
        productDescriptor {
            code = "PSTAGECRAFT"
            releaseDate = "20261003"
            // Marketplace wants the release line as an integer of at least two digits (major and
            // minor digits: "10" is 1.0; "01" is rejected). A licence covers every release up to the
            // purchased line, so this is the line the first paid release is sold as - and
            // verifyPluginStructure requires the plugin version to start with it (1.0.x).
            releaseVersion = "10"
        }
    }
    buildSearchableOptions = false

    // §9.8 / §13: the build compiles against 2025.2 but claims 2024.3+, so the claim is checked by
    // JetBrains' Plugin Verifier against the IDEs that range covers: `./gradlew verifyPlugin`
    // (downloads each IDE once). It flags any API the oldest supported IDE lacks.
    pluginVerification {
        ides {
            recommended()
        }
    }
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

/**
 * §11 Days 13-14: the compatibility-matrix probe. One run per (Jenkins version x configuration)
 * cell; it exercises the exact API surface Stagecraft uses and prints PASS/FAIL/SKIP per check.
 *
 *   STAGECRAFT_USER=admin STAGECRAFT_TOKEN=... \
 *     ./gradlew compatProbe --args="--base=http://localhost:18080 --build=http://localhost:18080/job/multibranch-demo/job/main/1/"
 */
tasks.register<JavaExec>("compatProbe") {
    group = "stagecraft"
    description = "Run the Day-13 compatibility probe against one Jenkins, one matrix cell at a time."
    mainClass = "dev.stagecraft.cli.CompatProbeKt"
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
}
