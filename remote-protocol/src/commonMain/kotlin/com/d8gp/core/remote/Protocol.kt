package com.d8gp.core.remote

import com.d8gp.core.matrix.MatrixAccount
import com.d8gp.core.matrix.MatrixMessage
import com.d8gp.core.matrix.MatrixRoomSummary
import com.d8gp.core.matrix.MatrixSyncState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// What a remote (the iPhone, later Android in remote mode) and the desktop
// server say to each other. One JSON text frame per message over a WebSocket.
//
// The shape deliberately echoes the plugin bridge — request/response by id plus
// server-pushed events — because a remote is a Source-API consumer whose bridge
// crosses a device boundary.

const val REMOTE_PROTOCOL_VERSION = 1

val RemoteJson: Json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true // a newer server may add fields; an older remote keeps working
    classDiscriminator = "t"
}

@Serializable
sealed interface RemoteRequest {
    /** Always first: presents the pairing token the QR carried. */
    @Serializable
    @SerialName("hello")
    data class Hello(val token: String, val deviceName: String, val protocolVersion: Int = REMOTE_PROTOCOL_VERSION) : RemoteRequest

    @Serializable
    @SerialName("rooms")
    data object ListRooms : RemoteRequest

    @Serializable
    @SerialName("timeline")
    data class Timeline(val roomId: String, val limit: Int = 50) : RemoteRequest

    /** Start (or stop) receiving [RemoteEvent.TimelineAppended] for a room. */
    @Serializable
    @SerialName("watch")
    data class Watch(val roomId: String, val watch: Boolean = true) : RemoteRequest

    @Serializable
    @SerialName("send")
    data class Send(val roomId: String, val body: String) : RemoteRequest
}

@Serializable
sealed interface RemoteResponse {
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val account: MatrixAccount,
        val serverName: String,
        val protocolVersion: Int = REMOTE_PROTOCOL_VERSION,
    ) : RemoteResponse

    @Serializable
    @SerialName("rooms")
    data class Rooms(val rooms: List<MatrixRoomSummary>) : RemoteResponse

    @Serializable
    @SerialName("timeline")
    data class Timeline(val roomId: String, val messages: List<MatrixMessage>) : RemoteResponse

    @Serializable
    @SerialName("sent")
    data class Sent(val roomId: String, val transactionId: String) : RemoteResponse

    @Serializable
    @SerialName("ok")
    data object Ok : RemoteResponse

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : RemoteResponse
}

object RemoteErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val UNSUPPORTED_VERSION = "unsupported-version"
    const val NOT_FOUND = "not-found"
    const val SERVER_ERROR = "server-error"
}

@Serializable
sealed interface RemoteEvent {
    @Serializable
    @SerialName("rooms-changed")
    data class RoomsChanged(val rooms: List<MatrixRoomSummary>) : RemoteEvent

    @Serializable
    @SerialName("timeline-appended")
    data class TimelineAppended(val roomId: String, val messages: List<MatrixMessage>) : RemoteEvent

    @Serializable
    @SerialName("sync-state")
    data class SyncState(val state: MatrixSyncState) : RemoteEvent
}

/** The envelope on the wire: a request and its response share an [id]. */
@Serializable
sealed interface RemoteFrame {
    @Serializable
    @SerialName("req")
    data class Req(val id: String, val request: RemoteRequest) : RemoteFrame

    @Serializable
    @SerialName("res")
    data class Res(val id: String, val response: RemoteResponse) : RemoteFrame

    @Serializable
    @SerialName("ev")
    data class Ev(val event: RemoteEvent) : RemoteFrame
}

fun encodeRemoteFrame(frame: RemoteFrame): String = RemoteJson.encodeToString(RemoteFrame.serializer(), frame)

/** Returns null for anything that is not a frame, so a bad peer cannot throw into the read loop. */
fun decodeRemoteFrame(text: String): RemoteFrame? =
    runCatching { RemoteJson.decodeFromString(RemoteFrame.serializer(), text) }.getOrNull()
