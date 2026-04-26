package com.auracode.assistant.toolwindow.shell

import java.nio.file.Files
import java.nio.file.Path
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkikoLibraryPathBootstrapTest {
    @Test
    fun `falls back to jar path from class resource url`() {
        val location = SkikoLibraryPathBootstrap.fallbackCodeSourceLocation(
            fallbackClass = InitializeSkikoLibraryPathEarly::class.java,
            classResource = URL(
                "jar:file:/E:/Aura/build/distributions/Aura-Code-1.0.0.zip!/" +
                    "com/auracode/assistant/toolwindow/shell/InitializeSkikoLibraryPathEarly.class",
            ),
        )

        assertNotNull(location)
        assertEquals("file:/E:/Aura/build/distributions/Aura-Code-1.0.0.zip", location.toString())
    }

    @Test
    fun `falls back to classes root from file resource url`() {
        val location = SkikoLibraryPathBootstrap.fallbackCodeSourceLocation(
            fallbackClass = InitializeSkikoLibraryPathEarly::class.java,
            classResource = URL("file:/E:/Aura/build/classes/kotlin/main/com/auracode/assistant/toolwindow/shell/InitializeSkikoLibraryPathEarly.class"),
        )

        assertNotNull(location)
        assertEquals("file:/E:/Aura/build/classes/kotlin/main/", location.toString())
    }

    @Test
    fun `configures property before preloading skiko`() {
        val libDir = Files.createTempDirectory("skiko-lib-preload")
        val extractionRoot = Files.createTempDirectory("skiko-extract-preload")
        val runtimeJar = libDir.resolve("skiko-awt-runtime-windows-x64-0.8.18.jar")
        val events = mutableListOf<String>()
        writeRuntimeJar(
            runtimeJar,
            mapOf(
                "skiko-windows-x64.dll" to "dll",
                "icudtl.dat" to "icu",
            ),
        )

        val extracted = SkikoLibraryPathBootstrap.configureAndPreloadIfNeeded(
            codeSourceLocation = libDir.toUri(),
            extractionRoot = extractionRoot,
            osName = "Windows 10",
            osArch = "amd64",
            currentPropertyValue = null,
            setProperty = { _, _ -> events += "set" },
            preloadLibrary = { events += "preload" },
        )

        assertNotNull(extracted)
        assertEquals(listOf("set", "preload"), events)
    }

    @Test
    fun `extracts windows runtime jar into dedicated directory`() {
        val libDir = Files.createTempDirectory("skiko-lib")
        val extractionRoot = Files.createTempDirectory("skiko-extract")
        val runtimeJar = libDir.resolve("skiko-awt-runtime-windows-x64-0.8.18.jar")
        writeRuntimeJar(
            runtimeJar,
            mapOf(
                "skiko-windows-x64.dll" to "dll",
                "icudtl.dat" to "icu",
            ),
        )

        val extracted = SkikoLibraryPathBootstrap.extractRuntimeDirectory(
            libDir = libDir,
            extractionRoot = extractionRoot,
            osName = "Windows 10",
            osArch = "amd64",
        )

        assertNotNull(extracted)
        assertTrue(extracted.resolve("skiko-windows-x64.dll").exists())
        assertEquals("dll", extracted.resolve("skiko-windows-x64.dll").readText())
        assertEquals("icu", extracted.resolve("icudtl.dat").readText())
    }

    @Test
    fun `returns null when plugin runtime jar is missing`() {
        val libDir = Files.createTempDirectory("skiko-lib-empty")
        val extractionRoot = Files.createTempDirectory("skiko-extract-empty")

        val extracted = SkikoLibraryPathBootstrap.extractRuntimeDirectory(
            libDir = libDir,
            extractionRoot = extractionRoot,
            osName = "Windows 10",
            osArch = "amd64",
        )

        assertNull(extracted)
    }

    @Test
    fun `chooses windows arm runtime coordinates for arm64`() {
        val prefix = SkikoLibraryPathBootstrap.runtimeJarPrefix(
            osName = "Windows 11",
            osArch = "aarch64",
        )

        assertEquals("skiko-awt-runtime-windows-arm64-", prefix)
    }

    private fun writeRuntimeJar(target: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
