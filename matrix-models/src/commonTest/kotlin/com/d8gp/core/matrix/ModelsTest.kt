package com.d8gp.core.matrix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

// These models cross the device boundary to the iPhone, so their wire form is
// part of the contract: defaults must stay optional and names must not drift.
class ModelsTest {
    private val json = Json

    @Test
    fun `room summary round trips and omits defaults`() {
        val room = MatrixRoomSummary(roomId = "!abc:matrix.org", name = "Charles", isDirect = true, encrypted = true)
        val encoded = json.encodeToString(MatrixRoomSummary.serializer(), room)
        assertEquals(room, json.decodeFromString(MatrixRoomSummary.serializer(), encoded))
        assertTrue("avatarUrl" !in encoded, "absent fields should not be written: $encoded")
    }

    @Test
    fun `message round trips`() {
        val message = MatrixMessage(
            eventId = "\$evt",
            roomId = "!abc:matrix.org",
            sender = "@mynah:matrix.org",
            senderName = "Mynah",
            body = "hello",
            timestamp = 1_726_700_000_000,
            mine = false,
        )
        val encoded = json.encodeToString(MatrixMessage.serializer(), message)
        assertEquals(message, json.decodeFromString(MatrixMessage.serializer(), encoded))
    }

    @Test
    fun `an undecrypted message keeps its place in the timeline`() {
        val pending = MatrixMessage(
            eventId = "\$evt",
            roomId = "!abc:matrix.org",
            sender = "@someone:matrix.org",
            senderName = "someone",
            body = "",
            timestamp = 1,
            mine = false,
            decrypted = false,
        )
        val decoded = json.decodeFromString(
            MatrixMessage.serializer(),
            json.encodeToString(MatrixMessage.serializer(), pending),
        )
        assertEquals(false, decoded.decrypted)
    }
}
