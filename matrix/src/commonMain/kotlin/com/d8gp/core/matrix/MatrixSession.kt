package com.d8gp.core.matrix

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.flattenValues
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.room.toFlowList
import de.connect2x.trixnity.client.store.Room
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.roomId
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.clientserverapi.client.classicLoginWithPassword
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.clientserverapi.model.user.ProfileField
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// The Matrix account as d8gp uses it: log in once, sync in the background, and
// expose rooms and timelines as flows of host-agnostic models. Everything
// Trixnity-shaped stops at this class, so the server, a future Android host and
// the remote protocol all speak the same small vocabulary.
class MatrixSession private constructor(
    private val client: MatrixClient,
    val homeserver: String,
) {
    val userId: String get() = client.userId.full

    val syncState: Flow<MatrixSyncState> = client.syncState.map {
        when (it) {
            SyncState.INITIAL_SYNC -> MatrixSyncState.INITIAL_SYNC
            SyncState.STARTED -> MatrixSyncState.STARTED
            SyncState.RUNNING -> MatrixSyncState.RUNNING
            SyncState.ERROR -> MatrixSyncState.ERROR
            SyncState.STOPPED, SyncState.TIMEOUT -> MatrixSyncState.STOPPED
        }
    }

    suspend fun account(): MatrixAccount =
        MatrixAccount(
            userId = userId,
            displayName = client.api.user.getProfile(client.userId).getOrNull()?.get(ProfileField.DisplayName)?.value,
            homeserver = homeserver,
        )

    suspend fun startSync() = client.startSync()

    suspend fun close() = client.closeSuspending()

    /** Joined rooms, newest activity first. */
    fun rooms(): Flow<List<MatrixRoomSummary>> =
        client.room.getAll().flattenValues().map { rooms ->
            rooms.filter { it.membership == Membership.JOIN }
                .sortedByDescending { it.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L }
                .map { it.toSummary() }
        }

    /** Rooms this account has been invited to but not yet joined. */
    fun invites(): Flow<List<MatrixRoomSummary>> =
        client.room.getAll().flattenValues().map { rooms ->
            rooms.filter { it.membership == Membership.INVITE }.map { it.toSummary() }
        }

    suspend fun join(roomId: String) {
        client.api.room.joinRoom(RoomId(roomId)).getOrThrow()
    }

    /** The last [limit] messages of a room, updating as they arrive. */
    fun timeline(roomId: String, limit: Int = 50): Flow<List<MatrixMessage>> =
        client.room.getLastTimelineEvents(RoomId(roomId))
            .toFlowList(MutableStateFlow(limit))
            .map { events -> events.map { it.first() }.reversed().mapNotNull { it.toMessage() } }

    suspend fun send(roomId: String, body: String): String =
        client.room.sendMessage(RoomId(roomId)) { text(body) }

    private fun Room.toSummary(): MatrixRoomSummary =
        MatrixRoomSummary(
            roomId = roomId.full,
            name = name?.explicitName
                ?: name?.heroes?.joinToString { it.full }?.takeIf { it.isNotBlank() }
                ?: roomId.full,
            isDirect = isDirect,
            encrypted = encrypted,
            avatarUrl = avatarUrl,
            lastMessageAt = lastRelevantEventTimestamp?.toEpochMilliseconds(),
        )

    private suspend fun TimelineEvent.toMessage(): MatrixMessage? {
        val event = event
        val content = content?.getOrNull()
        val body = when {
            content is RoomMessageEventContent -> content.body
            content == null -> null // encrypted, keys not here yet
            else -> return null // state events and everything else
        }
        val sender = event.sender
        return MatrixMessage(
            eventId = event.id.full,
            roomId = event.roomId.full,
            sender = sender.full,
            senderName = client.user.getById(event.roomId, sender).first()?.name ?: sender.full,
            body = body ?: "",
            timestamp = event.originTimestamp,
            mine = sender == client.userId,
            decrypted = body != null,
        )
    }

    companion object {
        /**
         * Restores the session saved under [dataPath], or null when there is
         * none. The password never reaches this path: the saved access token
         * and device keys are enough.
         */
        suspend fun restore(dataPath: String, homeserver: String): MatrixSession? =
            runCatching {
                MatrixClient.create(
                    repositoriesModule = matrixRepositoriesModule(dataPath),
                    mediaStoreModule = matrixMediaStoreModule("$dataPath/media"),
                    cryptoDriverModule = CryptoDriverModule.vodozemac(),
                ).getOrThrow()
            }.getOrNull()?.let { MatrixSession(it, homeserver) }

        /**
         * Logs in with a password and saves the session under [dataPath]. The
         * password is used once, here, and is never stored by d8gp.
         */
        suspend fun login(
            dataPath: String,
            homeserver: String,
            username: String,
            password: String,
            deviceDisplayName: String = "d8gp server",
        ): MatrixSession {
            val auth = MatrixClientAuthProviderData.classicLoginWithPassword(
                baseUrl = Url(homeserver),
                identifier = IdentifierType.User(username),
                password = password,
                initialDeviceDisplayName = deviceDisplayName,
            ).getOrThrow()
            val client = MatrixClient.create(
                repositoriesModule = matrixRepositoriesModule(dataPath),
                mediaStoreModule = matrixMediaStoreModule("$dataPath/media"),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = auth,
            ).getOrThrow()
            return MatrixSession(client, homeserver)
        }
    }
}
