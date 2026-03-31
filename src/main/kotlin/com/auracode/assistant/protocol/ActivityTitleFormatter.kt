package com.auracode.assistant.protocol

internal object ActivityTitleFormatter {
    data class TitlePresentation(
        val title: String,
        val targetLabel: String? = null,
        val targetPath: String? = null,
    )

    fun commandTitle(
        explicitName: String? = null,
        command: String? = null,
        body: String? = null,
    ): String {
        return commandPresentation(
            explicitName = explicitName,
            command = command,
            body = body,
        ).title
    }

    fun commandPresentation(
        explicitName: String? = null,
        command: String? = null,
        body: String? = null,
    ): TitlePresentation {
        val candidate = explicitName?.trim().orEmpty()
        val rawCommand = command?.trim().takeUnless { it.isNullOrBlank() }
            ?: body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val normalized = rawCommand?.unwrapCommandWrapper().orEmpty()
        if (normalized.isBlank()) {
            return TitlePresentation(
                title = candidate.takeUnless {
                    it.isBlank() || it.equals("Exec Command", ignoreCase = true)
                } ?: "Run command",
            )
        }

        val summarized = summarizeCommandPresentation(normalized)
        if (summarized != null) {
            return summarized
        }

        val fallback = fallbackCommandTitle(normalized)
        if (fallback != null) return TitlePresentation(title = fallback)

        return TitlePresentation(
            title = if (candidate.isNotBlank() && !candidate.equals("Exec Command", ignoreCase = true)) {
                candidate
            } else {
                "Run command"
            },
        )
    }

    fun toolTitle(
        explicitName: String? = null,
        body: String? = null,
        status: ItemStatus? = null,
    ): String {
        return toolPresentation(explicitName = explicitName, body = body, status = status).title
    }

    fun toolPresentation(
        explicitName: String? = null,
        body: String? = null,
        status: ItemStatus? = null,
    ): TitlePresentation {
        val candidate = explicitName?.trim().orEmpty()
        if (isWebSearchTool(explicitName = candidate)) {
            return TitlePresentation(title = webSearchTitle(status))
        }
        if (candidate.isShellLikeToolName()) {
            return commandPresentation(
                explicitName = null,
                body = body,
            )
        }

        val normalized = body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.unwrapCommandWrapper().orEmpty()
        val summarized = normalized.takeIf { it.isNotBlank() }?.let(::summarizeCommandPresentation)
        if (summarized != null && candidate.isBlank()) {
            return summarized
        }

        return TitlePresentation(title = candidate.ifBlank { "Tool Call" })
    }

    internal fun isWebSearchTool(
        explicitName: String? = null,
    ): Boolean {
        val normalizedName = explicitName?.trim()?.lowercase().orEmpty()
        return normalizedName == "web_search" || normalizedName == "websearch" || normalizedName == "web-search"
    }

    internal fun webSearchTitle(status: ItemStatus?): String {
        return if (status == ItemStatus.SUCCESS) {
            "Searched"
        } else {
            "Searching the web"
        }
    }

    private fun summarizeCommandPresentation(normalizedCommand: String): TitlePresentation? {
        summarizeFileWrite(normalizedCommand)?.let { return it }
        summarizeFileRead(normalizedCommand)?.let { return it }
        summarizePythonHeredoc(normalizedCommand)?.let { return TitlePresentation(title = it) }
        summarizeGroupedRipgrep(normalizedCommand)?.let { return TitlePresentation(title = it) }

        val effectiveCommand = extractEffectiveCommand(normalizedCommand) ?: return null
        val args = effectiveCommand.tokenizeShellLike()
        if (args.isEmpty()) return null

        val executable = args.first().commandLeaf()
        val targetFile = args.drop(1).firstOrNull { token ->
            token.isLikelyFilePath()
        }?.trim('\'', '"')

        return when (executable) {
            "cat", "type", "sed", "head", "tail", "nl" ->
                targetFile?.let { fileTitle("Read", it) } ?: TitlePresentation(title = "Read file")
            "ls" -> TitlePresentation(title = "List files")
            "dir" -> TitlePresentation(title = "List files")
            "find" -> TitlePresentation(title = "Search files")
            "rg" -> TitlePresentation(title = rgSummary(args))
            "get-childitem" -> TitlePresentation(title = powershellSearchSummary(args))
            "set-content" ->
                targetFile?.takeIf { it.isAbsoluteFilePath() }?.let { fileTitle("Edit", it) } ?: TitlePresentation(title = "Edit file")
            "add-content" ->
                targetFile?.takeIf { it.isAbsoluteFilePath() }?.let { fileTitle("Edit", it) } ?: TitlePresentation(title = "Edit file")
            "git" -> TitlePresentation(title = args.getOrNull(1)?.let { "Run Git ${it.humanizeToken()}" } ?: "Run Git")
            "gradlew", "./gradlew" -> TitlePresentation(title = args.getOrNull(1)?.let { "Run Gradle ${it.humanizeToken()}" } ?: "Run Gradle")
            "python", "python3" -> TitlePresentation(title = "Run Python")
            "java", "javac" -> TitlePresentation(title = "Run Java")
            "node", "nodejs" -> TitlePresentation(title = "Run Node")
            "powershell", "pwsh" -> TitlePresentation(title = "Run PowerShell")
            else -> {
                TitlePresentation(title = genericCommandTitle(executable))
            }
        }
    }

    private fun summarizeFileRead(command: String): TitlePresentation? {
        val trimmed = command.trim()
        val readPath = when {
            trimmed.startsWith("sed ", ignoreCase = true) ->
                Regex("""\s([^\s]+)$""").find(trimmed)?.groupValues?.getOrNull(1)
            trimmed.startsWith("head ", ignoreCase = true) ->
                Regex("""\s([^\s]+)$""").find(trimmed)?.groupValues?.getOrNull(1)
            trimmed.startsWith("tail ", ignoreCase = true) ->
                Regex("""\s([^\s]+)$""").find(trimmed)?.groupValues?.getOrNull(1)
            trimmed.startsWith("cat ", ignoreCase = true) ->
                Regex("""^cat\s+([^\s]+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)
            trimmed.startsWith("type ", ignoreCase = true) ->
                Regex("""^type\s+([^\s]+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)
            trimmed.startsWith("nl ", ignoreCase = true) ->
                Regex("""\s([^\s]+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)
            else -> null
        }?.trim('"', '\'')
        return readPath?.let { fileTitle("Read", it) }
    }

    private fun summarizeFileWrite(command: String): TitlePresentation? {
        val trimmed = command.trim()
        val unixMatch = Regex("""cat\s*(>>|>)\s*([^\s<]+)\s*<<""", RegexOption.IGNORE_CASE).find(trimmed)
        if (unixMatch != null) {
            val op = unixMatch.groupValues[1]
            val path = unixMatch.groupValues[2].trim('"', '\'')
            return if (path.isAbsoluteFilePath()) {
                fileTitle("Edit", path)
            } else {
                TitlePresentation(title = "Edit ${fileDisplayName(path)}")
            }
        }

        val psSet = Regex("""^(?:set-content|sc)\s+([^\s]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (psSet != null) {
            val path = psSet.groupValues[1].trim('"', '\'')
            return if (path.isAbsoluteFilePath()) fileTitle("Edit", path) else TitlePresentation(title = "Edit ${fileDisplayName(path)}")
        }
        val psAdd = Regex("""^(?:add-content|ac)\s+([^\s]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (psAdd != null) {
            val path = psAdd.groupValues[1].trim('"', '\'')
            return if (path.isAbsoluteFilePath()) fileTitle("Edit", path) else TitlePresentation(title = "Edit ${fileDisplayName(path)}")
        }
        return null
    }

    private fun fileTitle(action: String, path: String): TitlePresentation {
        val label = fileTargetLabel(path)
        return TitlePresentation(
            title = "$action $label",
            targetLabel = label,
            targetPath = path.trim().trim('"', '\''),
        )
    }

    private fun fileTargetLabel(path: String): String {
        val normalized = path.trim().trim('"', '\'').replace('\\', '/')
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return normalized
        val fileName = segments.last()
        return if (fileName.equals("SKILL.md", ignoreCase = true) && segments.size >= 2) {
            segments.takeLast(2).joinToString("/")
        } else {
            fileName
        }
    }

    private fun summarizePythonHeredoc(command: String): String? {
        val normalized = command.trim()
        if (!normalized.contains("python") || !normalized.contains("<<")) return null

        val deletedExts = Regex("""glob\(['"]\*\.(\w+)['"]\)""")
            .findAll(normalized)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
            .ifEmpty {
                Regex("""['"]\*\.(\w+)['"]""")
                    .findAll(normalized)
                    .map { it.groupValues[1].lowercase() }
                    .distinct()
                    .toList()
            }
        val deletesFiles = normalized.contains("unlink(") ||
            normalized.contains(".unlink()") ||
            normalized.contains("os.remove(") ||
            normalized.contains("rm(")

        if (deletesFiles && deletedExts.isNotEmpty()) {
            return "Delete ${joinFileKinds(deletedExts)} files"
        }

        return "Run Python"
    }

    private fun summarizeGroupedRipgrep(command: String): String? {
        val normalized = command.trim()
        if (!normalized.contains("rg")) return null
        val exts = Regex("""rg\s+--files\s+-g\s+['"]?\*?\.?(\w+)['"]?""")
            .findAll(normalized)
            .map { it.groupValues[1].lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (exts.isEmpty()) return null
        return "Search ${joinFileKinds(exts)} files"
    }

    private fun extractEffectiveCommand(command: String): String? {
        val candidates = command
            .replace("{", " ")
            .replace("}", " ")
            .split("&&", ";", "||")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.removeSuffix("|| true").removeSuffix("||true").trim() }
            .filterNot { it.equals("true", ignoreCase = true) }
            .filterNot { it.isShellStructureToken() }

        return candidates.lastOrNull { candidate ->
            val args = candidate.tokenizeShellLike()
            val executable = args.firstOrNull()?.substringAfterLast('/').orEmpty()
            executable.isNotBlank() &&
                !executable.equals("cd", ignoreCase = true) &&
                !executable.isShellStructureToken()
        }
    }

    private fun fallbackCommandTitle(command: String): String? {
        val effectiveCommand = extractEffectiveCommand(command) ?: return null
        val executable = effectiveCommand.tokenizeShellLike().firstOrNull()?.substringAfterLast('/').orEmpty()
        if (executable.isBlank() || executable.isShellStructureToken()) return null
        return when (executable.lowercase()) {
            "python", "python3" -> "Run Python"
            "java", "javac" -> "Run Java"
            "node", "nodejs" -> "Run Node"
            "git" -> "Run Git"
            "gradlew", "./gradlew" -> "Run Gradle"
            "sh", "bash", "zsh", "powershell", "pwsh" -> "Run command"
            else -> genericCommandTitle(executable)
        }
    }

    private fun genericCommandTitle(executable: String?): String {
        val normalized = executable.orEmpty().trim().trim('"', '\'').substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (normalized.isBlank() || normalized.isShellStructureToken()) return "Run command"
        return "Run command $normalized"
    }

    fun fileChangeTitle(
        explicitName: String? = null,
        changes: List<FileChangeSummary> = emptyList(),
        body: String? = null,
    ): String {
        val candidate = explicitName?.trim().orEmpty()
        val parsedChanges = if (changes.isNotEmpty()) changes else parseBodyChanges(body)
        if (parsedChanges.isEmpty()) {
            return if (candidate.isNotBlank() && !candidate.startsWith("File Changes", ignoreCase = true)) {
                candidate
            } else {
                "File Changes"
            }
        }

        if (parsedChanges.size == 1) {
            val change = parsedChanges.first()
            return "${change.kind.singleFileVerb()} ${fileDisplayName(change.path)}"
        }

        val distinctKinds = parsedChanges.map { it.kind.normalizedKind() }.distinct()
        return if (distinctKinds.size == 1) {
            "${distinctKinds.first().pluralVerb()} ${parsedChanges.size} files"
        } else {
            "Changed ${parsedChanges.size} files"
        }
    }

    data class FileChangeSummary(
        val path: String,
        val kind: String,
    )

    private fun parseBodyChanges(body: String?): List<FileChangeSummary> {
        return body.orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val kind = trimmed.substringBefore(' ', missingDelimiterValue = "").trim()
                val path = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
                if (kind.isBlank() || path.isBlank()) null else FileChangeSummary(path = path, kind = kind)
            }
            .toList()
    }

    private fun String.unwrapCommandWrapper(): String {
        val trimmed = trim()
        val unixSingle = Regex("""^(?:\S*/)?(?:sh|bash|zsh)\s+-lc\s+'([\s\S]+)'$""").matchEntire(trimmed)?.groupValues?.get(1)
        if (!unixSingle.isNullOrBlank()) return unixSingle
        val unixDouble = Regex("""^(?:\S*/)?(?:sh|bash|zsh)\s+-lc\s+"([\s\S]+)"$""").matchEntire(trimmed)?.groupValues?.get(1)
        if (!unixDouble.isNullOrBlank()) return unixDouble
        val cmd = Regex("""^(?:cmd(?:\.exe)?)\s+/c\s+([\s\S]+)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.groupValues?.get(1)
        if (!cmd.isNullOrBlank()) return cmd.trim('"')
        val powershell = Regex("""^(?:powershell|pwsh)(?:\.exe)?\s+-Command\s+([\s\S]+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.get(1)
        if (!powershell.isNullOrBlank()) return powershell.trim('"')
        return trimmed
    }

    private fun String.tokenizeShellLike(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .map { it.trim('\'', '"') }
            .filter { it.isNotBlank() }
    }

    private fun String.isLikelyFilePath(): Boolean {
        if (startsWith("-")) return false
        if (contains('*') || contains('|')) return false
        return contains('/') || contains('\\') || Regex("""^[A-Za-z]:\\""").containsMatchIn(this) || contains('.')
    }

    private fun String.isAbsoluteFilePath(): Boolean {
        val normalized = trim().trim('"', '\'')
        return normalized.startsWith("/") || Regex("""^[A-Za-z]:[\\/].+""").matches(normalized)
    }

    private fun rgSummary(args: List<String>): String {
        val fileGlob = args.windowed(size = 2, step = 1)
            .firstOrNull { it.firstOrNull() == "-g" }
            ?.getOrNull(1)
            ?.trim('\'', '"')
            .orEmpty()
        if (args.contains("--files") && fileGlob.isNotBlank()) {
            val ext = fileGlob.substringAfterLast('.', missingDelimiterValue = "").trimEnd('\'', '"', '*')
            if (ext.isNotBlank()) {
                return "Search $ext files"
            }
        }
        return "Search files"
    }

    private fun powershellSearchSummary(args: List<String>): String {
        val filter = args.windowed(size = 2, step = 1)
            .firstOrNull { it.firstOrNull()?.equals("-Filter", ignoreCase = true) == true }
            ?.getOrNull(1)
            ?.trim('\'', '"')
            .orEmpty()
        if (filter.startsWith("*.")) {
            val ext = filter.substringAfter("*.")
            if (ext.isNotBlank()) return "Search $ext files"
        }
        return "Search files"
    }

    private fun String.isShellLikeToolName(): Boolean {
        val normalized = trim().lowercase()
        return normalized == "shell" ||
            normalized == "local_shell" ||
            normalized == "exec command" ||
            normalized == "zsh" ||
            normalized == "bash" ||
            normalized == "sh" ||
            normalized == "cmd" ||
            normalized == "cmd.exe" ||
            normalized == "powershell" ||
            normalized == "pwsh" ||
            normalized.startsWith("cd ")
    }

    private fun String.isShellStructureToken(): Boolean {
        return when (trim().lowercase()) {
            "{", "}", "(", ")", "do", "done", "then", "fi", "if", "for", "while", "@echo", "rem" -> true
            else -> false
        }
    }

    private fun joinFileKinds(exts: List<String>): String {
        return when (exts.size) {
            0 -> ""
            1 -> exts.first()
            2 -> "${exts[0]} and ${exts[1]}"
            else -> exts.dropLast(1).joinToString(", ") + ", and " + exts.last()
        }
    }

    private fun String.humanizeToken(): String {
        return split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
    }

    private fun String.normalizedKind(): String {
        return trim().lowercase()
    }

    private fun String.singleFileVerb(): String {
        return when (normalizedKind()) {
            "create", "created", "add", "added" -> "Created"
            "delete", "deleted", "remove", "removed" -> "Deleted"
            "update", "updated", "modify", "modified" -> "Updated"
            else -> "Changed"
        }
    }

    private fun String.pluralVerb(): String {
        return when (normalizedKind()) {
            "create", "created", "add", "added" -> "Created"
            "delete", "deleted", "remove", "removed" -> "Deleted"
            "update", "updated", "modify", "modified" -> "Updated"
            else -> "Changed"
        }
    }

    private fun fileDisplayName(path: String): String {
        val normalized = path.trim().trim('"', '\'').replace('\\', '/')
        return normalized.substringAfterLast('/').ifBlank { normalized }
    }

    private fun String.commandLeaf(): String {
        val normalized = trim().trim('"', '\'')
        return normalized.substringAfterLast('/').substringAfterLast('\\').lowercase()
    }
}
