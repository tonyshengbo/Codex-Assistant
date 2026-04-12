plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.compose") version "1.7.3"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.auracode.assistant"
version = "1.0.0-beta.3"

repositories {
    google()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

configurations.matching { configuration ->
    configuration.isCanBeResolved && configuration.name.contains("runtimeClasspath", ignoreCase = true)
}.configureEach {
    resolutionStrategy.sortArtifacts(org.gradle.api.artifacts.ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.31.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m2:0.31.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    testRuntimeOnly("net.java.dev.jna:jna:5.14.0")
    testImplementation(kotlin("test"))
    intellijPlatform {
        intellijIdea("2024.3.4.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
    }
}
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
        }
        changeNotes = """
            <p>Aura Code 1.0.0-beta.3 improves release readiness and everyday IDE workflows.</p>
            <ul>
              <li>Adds Codex CLI version visibility, compatibility checks, and update notifications.</li>
              <li>Improves timeline usability with copy actions, fixed-choice input tooltips, and smoother collapsible message transitions.</li>
              <li>Introduces UI scaling support for better readability across different displays.</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            create("IU", "2024.3.4.1")
        }
    }
}
