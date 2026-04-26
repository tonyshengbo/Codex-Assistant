package com.auracode.assistant.provider.diagnostics

/**
 * Represents a provider-specific diagnostic fixture loaded from test resources.
 */
sealed class ProviderDiagnosticFixture(
    val resourcePath: String,
    val lines: List<String>,
) {
    /**
     * Represents a Claude diagnostic fixture.
     */
    class Claude(
        resourcePath: String,
        lines: List<String>,
    ) : ProviderDiagnosticFixture(resourcePath = resourcePath, lines = lines)

    /**
     * Represents a Codex diagnostic fixture.
     */
    class Codex(
        resourcePath: String,
        lines: List<String>,
    ) : ProviderDiagnosticFixture(resourcePath = resourcePath, lines = lines)

    companion object {
        /**
         * Loads a provider diagnostic fixture from the classpath and preserves raw line order.
         */
        fun load(resourcePath: String): ProviderDiagnosticFixture {
            val normalizedPath = normalizeResourcePath(resourcePath)
            val lines = requireNotNull(ProviderDiagnosticFixture::class.java.getResourceAsStream(normalizedPath)) {
                "Missing diagnostic fixture resource: $normalizedPath"
            }.bufferedReader().useLines { sequence ->
                sequence.map(String::trim)
                    .filter(String::isNotBlank)
                    .toList()
            }
            return when {
                normalizedPath.startsWith("/provider/claude/") -> Claude(
                    resourcePath = normalizedPath,
                    lines = lines,
                )

                normalizedPath.startsWith("/provider/codex/") -> Codex(
                    resourcePath = normalizedPath,
                    lines = lines,
                )

                else -> throw IllegalArgumentException("Unsupported diagnostic fixture path: $resourcePath")
            }
        }

        /**
         * Normalizes caller-provided classpath paths into a single absolute resource format.
         */
        private fun normalizeResourcePath(resourcePath: String): String {
            val trimmedPath = resourcePath.trim()
            require(trimmedPath.isNotEmpty()) { "Diagnostic fixture path must not be blank." }
            return if (trimmedPath.startsWith("/")) trimmedPath else "/$trimmedPath"
        }
    }
}
