plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.compose") version "1.7.3"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.auracode.assistant"
version = "1.0.0-beta.2"

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
            <p>First public beta release of Aura Code.</p>
            <ul>
              <li>Introduces a native Aura Code tool window inside IntelliJ IDEA.</li>
              <li>Adds project-scoped chat sessions with streaming responses, cancellation, and resumable history.</li>
              <li>Supports plan mode, approval mode, tool input prompts, and running-plan feedback.</li>
              <li>Includes file mentions, attachments, diff review, and edited-file aggregation workflows.</li>
              <li>Provides local Skills and MCP server management for Codex-based project workflows.</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            create("IU", "2024.3.4.1")
        }
    }
}
