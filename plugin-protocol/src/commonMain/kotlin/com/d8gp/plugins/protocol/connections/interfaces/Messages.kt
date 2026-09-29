package com.d8gp.plugins.protocol.connections.interfaces

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object MessagesInterface {
    const val ID = "d8gp.messages"
    const val MAJOR = 1L
    const val LIST = "list"
}

@Serializable
data class MessagesListRequest(
    val cursor: String? = null,
    val limit: Int? = null,
    val sinceMs: Long? = null,
)

@Serializable
data class SourceMessageHints(
    val gmailCategory: String? = null, // "promotions" | "social" | "updates" | "forums"
    val headers: Map<String, List<String>>? = null,
    val ics: List<String>? = null,
    val url: String? = null,
)

@Serializable
data class MessageParticipant(
    val id: String,
    val displayName: String,
    val photoUri: String? = null,
)

// UnifiedMessage plus the additive fields a message SOURCE may add. Identity
// is (source, id). The rich-messaging fields have no Kotlin twin in core and
// stay untyped.
@Serializable
data class SourceMessage(
    val protocol: String,
    val id: String,
    val conversationId: String,
    val sender: MessageParticipant? = null,
    val direction: String, // "in" | "out"
    val body: String,
    val timestamp: Long,
    val status: String? = null,
    val read: Boolean = false,
    val attachments: List<JsonElement> = emptyList(),
    val reactions: List<JsonElement>? = null,
    val quote: JsonElement? = null,
    val linkPreview: JsonElement? = null,
    val editedAt: Long? = null,
    val system: String? = null,
    val subject: String? = null,
    // Stamped by the HOST from the connection scope, never from provider payload.
    val source: String? = null,
    val hints: SourceMessageHints? = null,
)

@Serializable
data class MessageSourceStatus(
    val source: String,
    val state: String, // "ok" | "needs-auth" | "error"
)

@Serializable
data class MessagesListResponse(
    val messages: List<SourceMessage>,
    val cursor: String? = null,
    val more: Boolean = false,
    val sources: List<MessageSourceStatus> = emptyList(),
)
