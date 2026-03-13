package com.codex.assistant.service

import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineActionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimelineActionRuntime(
    private val scope: CoroutineScope,
    private val supportsCommandExecution: Boolean,
    private val commandExecutor: suspend (command: String, cwd: String) -> Pair<Int, String>,
    private val emitAction: (TimelineAction) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stateMutex = Mutex()
    private val pendingCommandJobs = linkedSetOf<Job>()
    private var finishRequested: Boolean = false
    private var finishEmitted: Boolean = false

    suspend fun accept(action: TimelineAction) {
        when (action) {
            is TimelineAction.CommandProposalReceived -> {
                if (!supportsCommandExecution) {
                    emitAction(action)
                    return
                }
                emitAction(
                    TimelineAction.UpsertCommand(
                        id = action.id,
                        command = action.command,
                        cwd = action.cwd,
                        status = TimelineActionStatus.RUNNING,
                        sequence = action.sequence,
                        timestampMs = action.timestampMs ?: clock(),
                    ),
                )
                launchCommand(action)
            }

            TimelineAction.FinishTurn -> emitFinishWhenPossible()
            else -> emitAction(action)
        }
    }

    suspend fun awaitIdle() {
        pendingJobsSnapshot().joinAll()
        emitFinishWhenPossible(force = true)
    }

    private suspend fun launchCommand(action: TimelineAction.CommandProposalReceived) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val (exitCode, output) = commandExecutor(action.command, action.cwd)
            emitAction(
                TimelineAction.UpsertCommand(
                    id = action.id,
                    command = action.command,
                    cwd = action.cwd,
                    output = output,
                    status = if (exitCode == 0) TimelineActionStatus.SUCCESS else TimelineActionStatus.FAILED,
                    sequence = action.sequence,
                    exitCode = exitCode,
                    timestampMs = clock(),
                ),
            )
            onPendingJobFinished(job)
        }

        stateMutex.withLock {
            pendingCommandJobs += job
        }
        job.start()
    }

    private suspend fun onPendingJobFinished(job: Job) {
        val shouldEmitFinish = stateMutex.withLock {
            pendingCommandJobs.remove(job)
            finishRequested && pendingCommandJobs.isEmpty() && !finishEmitted
        }
        if (shouldEmitFinish) {
            emitAction(TimelineAction.FinishTurn)
            stateMutex.withLock {
                finishEmitted = true
            }
        }
    }

    private suspend fun emitFinishWhenPossible(force: Boolean = false) {
        val shouldEmitFinish = stateMutex.withLock {
            finishRequested = true
            if (finishEmitted) {
                false
            } else if (pendingCommandJobs.isEmpty() || force) {
                finishEmitted = true
                true
            } else {
                false
            }
        }
        if (shouldEmitFinish) {
            emitAction(TimelineAction.FinishTurn)
        }
    }

    private suspend fun pendingJobsSnapshot(): List<Job> {
        return stateMutex.withLock { pendingCommandJobs.toList() }
    }
}
