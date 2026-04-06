package com.auracode.assistant.toolwindow.bootstrap

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.createDirectories

internal object SkikoLibraryPathBootstrap {
    private const val skikoLibraryPathProperty = "skiko.library.path"

    fun configureAndPreloadIfNeeded(
        codeSourceLocation: URI,
        extractionRoot: Path,
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
        currentPropertyValue: String? = System.getProperty(skikoLibraryPathProperty),
        setProperty: (String, String) -> Unit = System::setProperty,
        preloadLibrary: () -> Unit = ::preloadSkikoLibrary,
    ): Path? {
        val extracted = configureIfNeeded(
            codeSourceLocation = codeSourceLocation,
            extractionRoot = extractionRoot,
            osName = osName,
            osArch = osArch,
            currentPropertyValue = currentPropertyValue,
            setProperty = setProperty,
        ) ?: return null
        preloadLibrary()
        return extracted
    }

    fun configureIfNeeded(
        codeSourceLocation: URI,
        extractionRoot: Path,
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
        currentPropertyValue: String? = System.getProperty(skikoLibraryPathProperty),
        setProperty: (String, String) -> Unit = System::setProperty,
    ): Path? {
        val libDir = pluginLibDirectory(codeSourceLocation)
        val extracted = extractRuntimeDirectory(
            libDir = libDir,
            extractionRoot = extractionRoot,
            osName = osName,
            osArch = osArch,
        ) ?: return null
        val extractedPath = extracted.toAbsolutePath().normalize().toString()
        if (currentPropertyValue == extractedPath) return extracted
        setProperty(skikoLibraryPathProperty, extractedPath)
        return extracted
    }

    fun extractRuntimeDirectory(
        libDir: Path,
        extractionRoot: Path,
        osName: String,
        osArch: String,
    ): Path? {
        val prefix = runtimeJarPrefix(osName, osArch) ?: return null
        val runtimeJar = libDir.toFile()
            .listFiles()
            ?.firstOrNull { file -> file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".jar") }
            ?.toPath()
            ?: return null
        val targetDir = extractionRoot.resolve(runtimeJar.fileName.toString().removeSuffix(".jar"))
        extractJar(runtimeJar, targetDir)
        return targetDir
    }

    fun runtimeJarPrefix(
        osName: String,
        osArch: String,
    ): String? {
        if (!osName.startsWith("Windows", ignoreCase = true)) return null
        return if (osArch.equals("aarch64", ignoreCase = true) || osArch.equals("arm64", ignoreCase = true)) {
            "skiko-awt-runtime-windows-arm64-"
        } else {
            "skiko-awt-runtime-windows-x64-"
        }
    }

    private fun pluginLibDirectory(codeSourceLocation: URI): Path {
        val location = Path.of(codeSourceLocation)
        return if (location.toFile().isDirectory) location else location.parent
    }

    private fun extractJar(
        jarPath: Path,
        targetDir: Path,
    ) {
        targetDir.createDirectories()
        java.util.jar.JarFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val output = targetDir.resolve(entry.name).normalize()
                require(output.startsWith(targetDir)) { "Refusing to extract outside $targetDir" }
                output.parent?.createDirectories()
                jar.getInputStream(entry).use { input ->
                    java.nio.file.Files.copy(
                        input,
                        output,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        }
    }

    private fun preloadSkikoLibrary() {
        val libraryClass = Class.forName("org.jetbrains.skiko.Library", true, javaClass.classLoader)
        val instance = libraryClass.getField("INSTANCE").get(null)
        libraryClass.getMethod("load").invoke(instance)
    }

    fun codeSourceLocation(fallbackClass: Class<*>): URI? {
        val direct = fallbackClass.protectionDomain?.codeSource?.location?.toURI()
        if (direct != null) return direct
        return fallbackCodeSourceLocation(
            fallbackClass = fallbackClass,
            classResource = fallbackClass.getResource("${fallbackClass.simpleName}.class"),
        )
    }

    fun fallbackCodeSourceLocation(
        fallbackClass: Class<*>,
        classResource: URL?,
    ): URI? {
        val resource = classResource ?: return null
        val externalForm = resource.toExternalForm()
        return when {
            externalForm.startsWith("jar:") -> URI(externalForm.substringAfter("jar:").substringBefore("!/"))
            externalForm.startsWith("file:") -> {
                val suffix = fallbackClass.name.replace('.', '/') + ".class"
                val root = externalForm.substringBefore(suffix)
                URI(root)
            }
            else -> null
        }
    }
}

class InitializeSkikoLibraryPathEarly : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        runCatching {
            val root = Path.of(PathManager.getTempPath(), "aura-code", "skiko")
            root.createDirectories()
            val codeSourceLocation = SkikoLibraryPathBootstrap.codeSourceLocation(javaClass)
                ?: error("Unable to resolve plugin code source location")
            SkikoLibraryPathBootstrap.configureAndPreloadIfNeeded(
                codeSourceLocation = codeSourceLocation,
                extractionRoot = root,
            )
        }.onFailure { throwable ->
            thisLogger().warn("Failed to configure skiko.library.path early", throwable)
        }
    }
}
