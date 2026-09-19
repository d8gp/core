package com.d8gp.core.remote

import com.d8gp.core.matrix.MatrixMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `frames round trip`() {
        val frames = listOf(
            RemoteFrame.Req("1", RemoteRequest.Hello(token = "tok", deviceName = "Charles's iPhone")),
            RemoteFrame.Req("2", RemoteRequest.ListRooms),
            RemoteFrame.Req("3", RemoteRequest.Send(roomId = "!r:matrix.org", body = "hello")),
            RemoteFrame.Res("3", RemoteResponse.Sent(roomId = "!r:matrix.org", transactionId = "txn")),
            RemoteFrame.Res("4", RemoteResponse.Error(RemoteErrorCodes.UNAUTHORIZED, "bad token")),
            RemoteFrame.Ev(
                RemoteEvent.TimelineAppended(
                    roomId = "!r:matrix.org",
                    messages = listOf(
                        MatrixMessage("\$e", "!r:matrix.org", "@a:matrix.org", "A", "hi", 1, mine = false),
                    ),
                ),
            ),
        )
        for (frame in frames) {
            assertEquals(frame, decodeRemoteFrame(encodeRemoteFrame(frame)), "round trip failed for $frame")
        }
    }

    @Test
    fun `junk never throws into the read loop`() {
        assertNull(decodeRemoteFrame("not json"))
        assertNull(decodeRemoteFrame("""{"t":"req"}"""))
        assertNull(decodeRemoteFrame("""{"t":"nope","id":"1"}"""))
    }

    @Test
    fun `unknown fields from a newer server are ignored`() {
        val frame = decodeRemoteFrame("""{"t":"res","id":"1","response":{"t":"ok","somethingNew":42}}""")
        assertEquals(RemoteFrame.Res("1", RemoteResponse.Ok), frame)
    }

    @Test
    fun `pairing offer round trips through its uri`() {
        val offer = PairingOffer(
            host = "192.168.1.20",
            port = 8443,
            certSha256 = "a".repeat(64),
            token = "one-time-token",
            serverName = "Charles's Mac",
        )
        assertEquals(offer, PairingOffer.parse(offer.toUri()))
    }

    @Test
    fun `a pairing uri without a real fingerprint is refused`() {
        assertNull(PairingOffer.parse("d8gp://pair?h=h&p=1&f=short&t=t"))
        assertNull(PairingOffer.parse("d8gp://pair?h=h&p=1&f=${"z".repeat(64)}&t=t"))
        assertNull(PairingOffer.parse("https://example.com/pair?h=h&p=1&f=${"a".repeat(64)}&t=t"))
        assertTrue(PairingOffer.parse("d8gp://pair?h=h&p=1&f=${"A".repeat(64)}&t=t") != null, "hex case should not matter")
    }
}
