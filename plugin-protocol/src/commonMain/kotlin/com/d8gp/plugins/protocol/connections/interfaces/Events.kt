package com.d8gp.plugins.protocol.connections.interfaces

import kotlinx.serialization.Serializable

object EventsInterface {
    const val ID = "d8gp.events"
    const val MAJOR = 1L
    const val LIST = "list"
}

@Serializable
data class EventsListRequest(
    val cursor: String? = null,
    val limit: Int? = null,
)

@Serializable
data class CalendarEventOrigin(
    val source: String,
    val messageId: String,
    val conversationId: String,
    val subject: String? = null,
    val url: String? = null,
    val category: String? = null,
)

@Serializable
data class CalendarEvent(
    val id: String,
    val seq: Long, // grows per upsert; the cursor is the last seq seen
    val title: String,
    val kind: String, // DEADLINE | EXAM | CLASS_CHANGE | EVENT | ADMIN_ACTION | TALK
    val startsAt: String, // YYYY-MM-DD when allDay, else YYYY-MM-DDTHH:mm local wall time
    val endsAt: String? = null, // inclusive last day when allDay
    val allDay: Boolean,
    val timezone: String,
    val location: String? = null,
    val course: String? = null,
    val confidence: Double,
    val evidence: String,
    val origin: String, // "model" | "ics"
    val from: CalendarEventOrigin,
    val extractorVersion: Int,
)

@Serializable
data class EventsListResponse(
    val events: List<CalendarEvent>,
    val cursor: String? = null,
    val more: Boolean = false,
)
