package com.d8gp.plugins.protocol.connections

import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

// VectorsGenerated.kt is a checked-in copy. This proves it still matches the
// vector files wherever the plugin repos are checked out, and is skipped
// where they are not.
class VectorsFreshnessTest {
    private val vectorsDir: File = File(
        System.getenv("D8GP_PLUGINS_ROOT") ?: "${System.getProperty("user.home")}/workspace/d8gp-plugins",
        "sdk-plugin/testing/vectors",
    )

    @Test
    fun `generated vectors match the vector files`() {
        assumeTrue("no vector files at $vectorsDir", vectorsDir.isDirectory)

        val files = vectorsDir.listFiles { file -> file.name.endsWith(".json") }!!.sortedBy { it.name }
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            digest.update(file.name.toByteArray())
            digest.update(0)
            digest.update(file.readBytes())
            digest.update(0)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }

        assertEquals(VectorsGenerated.FILES, files.map { it.name })
        assertEquals(
            VectorsGenerated.INPUT_DIGEST,
            hex,
            "VectorsGenerated.kt is stale: run node plugin-protocol/scripts/gen-vectors.mjs",
        )
    }
}
