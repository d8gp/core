package com.d8gp.core.matrix

import kotlinx.serialization.Serializable

// The shapes a remote sees. Deliberately free of Trixnity types: these cross
// the device boundary to the iPhone and are the same models an Android host
// would render, so they must stay serializable and host-agnostic.

@Serializable
data class MatrixRoomSummary(
    val roomId: String,
    val name: String,
    val isDirect: Boolean,
    val encrypted: Boolean,
    val avatarUrl: String? = null,
    val lastMessage: String? = null,
    val lastMessageAt: Long? = null,
    val unreadCount: Long = 0,
)

@Serializable
data class MatrixMessage(
    val eventId: String,
    val roomId: String,
    val sender: String,
    val senderName: String,
    val body: String,
    val timestamp: Long,
    val mine: Boolean,
    // An encrypted event whose keys have not arrived yet: shown as a placeholder
    // rather than dropped, so a gap in the timeline is visible.
    val decrypted: Boolean = true,
)

@Serializable
enum class MatrixSyncState { INITIAL_SYNC, STARTED, RUNNING, ERROR, STOPPED }

@Serializable
data class MatrixAccount(val userId: String, val displayName: String? = null, val homeserver: String)
