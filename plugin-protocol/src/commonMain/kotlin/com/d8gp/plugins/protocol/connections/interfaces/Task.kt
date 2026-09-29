package com.d8gp.plugins.protocol.connections.interfaces

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Host-only: the broker never creates a connection for this interface.
object TaskInterface {
    const val ID = "d8gp.task"
    const val MAJOR = 1L
    const val RUN = "run"
    const val STATUS = "status"
}

@Serializable
data class TaskRunOptions(
    val limit: Int? = null,
    val sinceMs: Long? = null,
    val dryRun: Boolean = true,
)

@Serializable
data class TaskRunRequest(
    val runId: String,
    val budgetMs: Long,
    val cancel: Boolean = false,
    val options: TaskRunOptions = TaskRunOptions(),
)

// One bounded STEP of a run; the host calls again until done.
@Serializable
data class TaskRunResponse(
    val done: Boolean,
    val phase: String? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val waitMs: Long = 0,
    val report: JsonObject? = null,
    val fatal: String? = null,
)

@Serializable
data class TaskStatusResponse(
    val state: String, // idle | running | needs-wiring | needs-config | unavailable
    val lastReport: JsonObject? = null,
)
