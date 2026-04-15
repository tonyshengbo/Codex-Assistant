package com.auracode.assistant.commit

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitMessageContextServiceTest {
    @Test
    fun `collects change summary and included file paths`() {
        val service = CommitMessageContextService()
        val change = change(
            beforePath = "src/Main.kt",
            beforeContent = "fun oldName() = 1\n",
            afterPath = "src/Main.kt",
            afterContent = "fun newName() = 2\n",
        )

        val context = service.collect(
            includedChanges = listOf(change),
            includedUnversionedFiles = listOf("README.md"),
        )

        val resolved = assertNotNull(context)
        assertEquals(listOf("README.md", "src/Main.kt"), resolved.includedFilePaths)
        val summary = assertNotNull(resolved.changeSummary)
        assertTrue(summary.contains("MODIFICATION: src/Main.kt"), summary)
        assertTrue(summary.contains("- fun oldName() = 1"), summary)
        assertTrue(summary.contains("+ fun newName() = 2"), summary)
    }

    @Test
    fun `returns file context when summary is unavailable but files exist`() {
        val service = CommitMessageContextService(
            changeSummaryProvider = { null },
            includedFilePathsProvider = { emptyList() },
        )

        val context = service.collect(
            includedChanges = emptyList(),
            includedUnversionedFiles = listOf("README.md"),
        )

        val resolved = assertNotNull(context)
        assertEquals(listOf("README.md"), resolved.includedFilePaths)
        assertNull(resolved.changeSummary)
    }

    @Test
    fun `returns null when there is no summary and no included files`() {
        val service = CommitMessageContextService(
            changeSummaryProvider = { "" },
            includedFilePathsProvider = { emptyList() },
        )

        val context = service.collect(includedChanges = emptyList())

        assertNull(context)
    }

    private fun change(
        beforePath: String?,
        beforeContent: String?,
        afterPath: String?,
        afterContent: String?,
    ): Change {
        val sharedFilePath = beforePath
            ?.takeIf { it == afterPath }
            ?.let(::filePath)
        val beforeRevision = beforePath?.let { contentRevision(path = it, content = beforeContent, resolvedFilePath = sharedFilePath) }
        val afterRevision = afterPath?.let { contentRevision(path = it, content = afterContent, resolvedFilePath = sharedFilePath) }
        return Change(beforeRevision, afterRevision)
    }

    private fun contentRevision(path: String, content: String?, resolvedFilePath: FilePath? = null): ContentRevision {
        val filePath = resolvedFilePath ?: filePath(path)
        return Proxy.newProxyInstance(
            ContentRevision::class.java.classLoader,
            arrayOf(ContentRevision::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getFile" -> filePath
                "getContent" -> content
                "getRevisionNumber" -> VcsRevisionNumber.NULL
                "toString" -> "ContentRevision($path)"
                else -> defaultValue(method.returnType)
            }
        } as ContentRevision
    }

    private fun filePath(path: String): FilePath {
        return Proxy.newProxyInstance(
            FilePath::class.java.classLoader,
            arrayOf(FilePath::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPath",
                "getPresentableUrl",
                "toString",
                -> path
                "getName" -> path.substringAfterLast('/')
                "isDirectory",
                "isNonLocal",
                -> false
                "getVirtualFile",
                "getParentPath",
                "getDocument",
                "getCharset",
                "getFileType",
                "getFileSystem",
                -> null
                "refresh" -> null
                "hardRefresh" -> null
                else -> defaultValue(method.returnType)
            }
        } as FilePath
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when {
            type == java.lang.Boolean.TYPE -> false
            type == java.lang.Integer.TYPE -> 0
            type == java.lang.Long.TYPE -> 0L
            type == java.lang.Double.TYPE -> 0.0
            type == java.lang.Float.TYPE -> 0f
            type == java.lang.Short.TYPE -> 0.toShort()
            type == java.lang.Byte.TYPE -> 0.toByte()
            type == java.lang.Character.TYPE -> '\u0000'
            VcsException::class.java.isAssignableFrom(type) -> null
            else -> null
        }
    }
}
