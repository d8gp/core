package com.d8gp.plugins.protocol.connections

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("sha256.json", 23)
        for (vector in vectorList(VectorsGenerated.SHA256)) {
            val text = vector.text("utf8")
            report.check(vector.text("name") + " (bytes)", vector.long("bytes")) { utf8Length(text).toLong() }
            report.check(vector.text("name"), vector.text("hex")) { Sha256.hexOfUtf8(text) }
        }
        report.finishCounting(2)
    }

    @Test
    fun `hashes a million bytes across many blocks`() {
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun `digest is 32 raw bytes`() {
        assertEquals(32, Sha256.digest(ByteArray(0)).size)
    }
}
