package com.codex.assistant.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

@Service(Service.Level.PROJECT)
class SmartFileSearchService(private val project: Project) {

    fun searchByName(query: String, limit: Int = 20): List<String> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        return ReadAction.compute<List<String>, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val q = normalized.lowercase()

            val candidates = FilenameIndex.getAllFilenames(project).asSequence()
                .filter { it.contains(q, ignoreCase = true) }
                .flatMap { name ->
                    FilenameIndex.getVirtualFilesByName(project, name, scope).asSequence()
                }
                .filter { file -> !file.isDirectory }
                .filter { file -> MentionFileWhitelist.allowPath(file.path) }
                .distinctBy { it.path }
                .toList()

            val prefix = candidates.filter { it.name.startsWith(q, ignoreCase = true) }
            val contains = candidates.filter { !it.name.startsWith(q, ignoreCase = true) && it.name.contains(q, ignoreCase = true) }
            (prefix + contains).take(limit).map { it.path }
        }
    }

    companion object {
        fun getInstance(project: Project): SmartFileSearchService =
            project.getService(SmartFileSearchService::class.java)
    }
}
