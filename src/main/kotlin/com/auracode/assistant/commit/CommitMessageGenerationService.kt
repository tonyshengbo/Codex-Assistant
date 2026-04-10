package com.auracode.assistant.commit

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.notification.AuraNotificationGroup
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.settings.AgentSettingsService
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.vcs.commit.CommitWorkflowUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
internal class CommitMessageGenerationService private constructor(
    private val project: Project?,
    private val settings: AgentSettingsService,
    private val contextService: CommitMessageContextService?,
    private val applyService: CommitMessageApplyService,
    private val streamProvider: suspend (AgentRequest) -> Flow<UnifiedEvent>,
    private val workingDirectoryProvider: () -> String,
    private val failureNotifier: (String) -> Unit,
) : Disposable {
    constructor(project: Project) : this(
        project = project,
        settings = AgentSettingsService.getInstance(),
        contextService = project.getService(CommitMessageContextService::class.java),
        applyService = CommitMessageApplyService(),
        streamProvider = { request ->
            val registry = ProviderRegistry(AgentSettingsService.getInstance())
            registry.providerOrDefault(request.engineId).stream(request)
        },
        workingDirectoryProvider = { project.basePath ?: "." },
        failureNotifier = { message ->
            AuraNotificationGroup.chatCompletion()
                .createNotification(
                    AuraCodeBundle.message("action.generate.commit.message.text"),
                    message,
                    NotificationType.WARNING,
                )
                .notify(project)
        },
    )

    internal constructor(
        streamProvider: suspend (AgentRequest) -> Flow<UnifiedEvent>,
    ) : this(
        project = null,
        settings = AgentSettingsService(),
        contextService = null,
        applyService = CommitMessageApplyService(),
        streamProvider = streamProvider,
        workingDirectoryProvider = { "." },
        failureNotifier = {},
    )

    private val scope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "CommitMessageGenerationService",
        dispatcher = Dispatchers.IO,
        failureReporter = { _, label, error ->
            failureNotifier(
                error.message ?: "Coroutine failed${label?.let { " ($it)" }.orEmpty()}.",
            )
        },
    )
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun generateAndApply(
        commitWorkflowUi: CommitWorkflowUi,
        commitMessageControl: CommitMessageI,
    ) {
        if (!running.compareAndSet(false, true)) return
        val commitMessageUi = commitWorkflowUi.commitMessageUi
        commitMessageUi.startLoading()
        val includedChanges = commitWorkflowUi.getIncludedChanges().toList()
        val includedUnversioned = commitWorkflowUi.getIncludedUnversionedFiles().map { it.path }
        scope.launch(label = "generateAndApply") {
            val result = runCatching {
                val collector = contextService
                    ?: error("Commit message context service is unavailable.")
                val context = collector.collect(
                    includedChanges = includedChanges,
                    includedUnversionedFiles = includedUnversioned,
                ) ?: error(AuraCodeBundle.message("action.generate.commit.message.error.empty"))
                generate(context)
            }
            ApplicationManager.getApplication().invokeLater {
                try {
                    result.onSuccess { message ->
                        applyService.apply(
                            message = message,
                            commitMessageControl = commitMessageControl,
                            commitMessageUi = commitMessageUi,
                        )
                    }.onFailure { error ->
                        failureNotifier(error.message ?: AuraCodeBundle.message("action.generate.commit.message.error.failed"))
                    }
                } finally {
                    commitMessageUi.stopLoading()
                    running.set(false)
                }
            }
        }
    }

    suspend fun generate(context: CommitMessageGenerationContext): String {
        val registry = ProviderRegistry(settings)
        val engineId = registry.defaultEngineId()
        val assistantBuffer = StringBuilder()
        val request = AgentRequest(
            engineId = engineId,
            model = settings.selectedComposerModel(),
            reasoningEffort = settings.selectedComposerReasoning(),
            prompt = CommitMessagePromptFactory.create(context),
            contextFiles = buildContextFiles(context),
            workingDirectory = workingDirectoryProvider(),
            approvalMode = AgentApprovalMode.AUTO,
        )
        streamProvider(request).collect { event ->
            when (event) {
                is UnifiedEvent.Error -> throw IllegalArgumentException(event.message)
                is UnifiedEvent.ItemUpdated -> {
                    val item = event.item
                    if (item.kind == ItemKind.NARRATIVE &&
                        item.name != "user_message" &&
                        item.name != "system_message" &&
                        !item.text.isNullOrBlank()
                    ) {
                        assistantBuffer.clear()
                        assistantBuffer.append(item.text)
                    }
                }
                else -> Unit
            }
        }
        val raw = assistantBuffer.toString()
            .ifBlank { throw IllegalArgumentException(AuraCodeBundle.message("action.generate.commit.message.error.failed")) }
        return CommitMessageOutputSanitizer.sanitize(raw)
    }

    private fun buildContextFiles(context: CommitMessageGenerationContext): List<ContextFile> {
        return buildList {
            context.stagedDiff?.let { add(ContextFile(path = "git/staged.diff", content = it)) }
            if (context.includedFilePaths.isNotEmpty()) {
                add(
                    ContextFile(
                        path = "git/included-files.txt",
                        content = context.includedFilePaths.joinToString(separator = "\n"),
                    ),
                )
            }
            context.branchName?.let { add(ContextFile(path = "git/branch.txt", content = it)) }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
