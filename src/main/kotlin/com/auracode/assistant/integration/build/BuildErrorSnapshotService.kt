package com.auracode.assistant.integration.build

import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class BuildErrorSnapshotService {
    @Volatile
    private var latestSnapshot: BuildErrorSnapshot? = null

    /**
     * Stores the latest build error snapshot so future entrypoints can reuse it.
     */
    fun remember(snapshot: BuildErrorSnapshot) {
        latestSnapshot = snapshot
    }

    fun latest(): BuildErrorSnapshot? = latestSnapshot
}
