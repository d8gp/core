package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ExecArg
import com.d8gp.plugins.protocol.ExecDecl
import com.d8gp.plugins.protocol.ExecStdin
import com.d8gp.plugins.protocol.PluginManifest
import com.d8gp.plugins.protocol.ProtocolJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class ExecTemplateTest {
    private val decl = ExecDecl(
        binary = "claude",
        purpose = "Runs a helper program.",
        argv = listOf(
            ExecArg.Literal("-p"),
            ExecArg.Slot(flag = "--system-prompt", slot = "system", maxBytes = 64),
            ExecArg.Literal("--tools="),
        ),
    )

    private fun slots(system: String) = JsonObject(mapOf("system" to JsonPrimitive(system)))

    @Test
    fun `matches every shared vector`() {
        val report = VectorReport("exec-template.json", 50)
        for (vector in vectorList(VectorsGenerated.EXEC_TEMPLATE)) {
            val expect = vector.getValue("expect").jsonObject
            val expected = expect.textOrNull("error")?.let { "error $it" }
                ?: "argv ${expect.getValue("argv").jsonArray.map { it.jsonPrimitive.content }}"
            report.check(vector.text("name"), expected) {
                val parsed = ProtocolJson.decodeFromJsonElement(ExecDecl.serializer(), vector.getValue("decl"))
                try {
                    "argv ${ExecTemplate.build(parsed, vector["slots"])}"
                } catch (e: ExecTemplateException) {
                    "error ${e.code}"
                }
            }
        }
        report.finish()
    }

    @Test
    fun `a hostile value beginning with two dashes stays inside its own element`() {
        for (hostile in listOf("--dangerously-skip-permissions", "--tools=Bash", "-p", "x --add-dir /")) {
            assertEquals(
                listOf("-p", "--system-prompt=$hostile", "--tools="),
                ExecTemplate.build(decl, slots(hostile)),
            )
        }
    }

    @Test
    fun `the digest follows the declaration and not the order it was written in`() {
        val reordered = ProtocolJson.decodeFromString(
            ExecDecl.serializer(),
            """{"argv":["-p",{"maxBytes":64,"slot":"system","flag":"--system-prompt"},"--tools="],
                "purpose":"Runs a helper program.","binary":"claude"}""",
        )
        assertEquals(ExecTemplate.argvDigest(decl), ExecTemplate.argvDigest(reordered))
        assertEquals(64, ExecTemplate.argvDigest(decl).length)

        val widened = decl.copy(argv = decl.argv + ExecArg.Literal("--add-dir=/"))
        assertNotEquals(ExecTemplate.argvDigest(decl), ExecTemplate.argvDigest(widened))
        assertNotEquals(ExecTemplate.argvDigest(decl), ExecTemplate.argvDigest(decl.copy(timeoutMs = 1)))
    }

    // The canonical text was written by hand from the rules in the vectors
    // README and hashed with node, so the digest does not come from this code.
    @Test
    fun `the digest is the sha256 of the canonical declaration without absent fields`() {
        val canonical = """{"argv":["-p",{"flag":"--system-prompt","maxBytes":64,"slot":"system"},"--tools="],""" +
            """"binary":"claude","purpose":"Runs a helper program."}"""
        assertEquals(canonical, CanonicalJson.encode(ProtocolJson.encodeToJsonElement(ExecDecl.serializer(), decl)))
        assertEquals("262693c2243c3bf370ab5e949b18224a11f0dcb2cf411621c679cf6a4f74fc85", ExecTemplate.argvDigest(decl))

        val full = decl.copy(
            argv = decl.argv + ExecArg.Slot(flag = "--model", slot = "model", enum = listOf("haiku", "opus")),
            stdin = ExecStdin(maxBytes = 524288),
            timeoutMs = 120000,
            maxStdoutBytes = 1048576,
        )
        assertEquals("6c619e8a8e822028eacac8e18900990bd715acebd6acfc7ce40c9c5cf883dfe7", ExecTemplate.argvDigest(full))
    }

    @Test
    fun `a declaration that breaks the manifest rules cannot exist`() {
        for (binary in listOf("../../bin/sh", "/bin/sh", "", "Claude", "a b", "sh\u0000", "x".repeat(33))) {
            assertFailsWith<IllegalArgumentException>(binary) { decl.copy(binary = binary) }
        }
        for (flag in listOf("-p --dangerously-skip-permissions --x", "-p", "--", "--a=b", "--a b", "model", "")) {
            assertFailsWith<IllegalArgumentException>(flag) { ExecArg.Slot(flag = flag, slot = "a") }
        }
        for (slot in listOf("", "A", "a b", "a-b", "a\u0000", "x".repeat(33))) {
            assertFailsWith<IllegalArgumentException>(slot) { ExecArg.Slot(flag = "--model", slot = slot) }
        }
        for (value in listOf("", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException> { ExecArg.Slot(flag = "--model", slot = "a", enum = listOf(value)) }
        }
        assertFailsWith<IllegalArgumentException> { ExecArg.Literal("lit\u0000eral") }
        assertEquals("", ExecArg.Literal("").value)
    }

    @Test
    fun `the serializer cannot build what the validator refuses`() {
        val entries = listOf(
            """{"binary":"../../bin/sh","purpose":"p","argv":[]}""",
            """{"binary":"sh","purpose":"p","argv":[{"flag":"-p --dangerously-skip-permissions --x","slot":"a"}]}""",
            """{"binary":"sh","purpose":"p","argv":[{"flag":"--a","slot":"Not A Slot"}]}""",
            """{"binary":"sh","purpose":"p","argv":[{"flag":"--a","slot":"a","enum":["ok","nul\u0000"]}]}""",
            """{"binary":"sh","purpose":"p","argv":["lit\u0000eral"]}""",
        )
        for (entry in entries) {
            assertFailsWith<IllegalArgumentException>(entry) {
                ProtocolJson.decodeFromString(ExecDecl.serializer(), entry)
            }
            assertFailsWith<IllegalArgumentException>(entry) {
                ProtocolJson.decodeFromString(
                    PluginManifest.serializer(),
                    """{"manifestVersion":1,"id":"plugin-x","name":"X","version":"0.1.0",
                        "entry":{"url":"https://plugins.d8gp.example/x/bundle.js","sha256":"${"a".repeat(64)}"},
                        "capabilities":[{"type":"connector","role":"requires","interface":"d8gp.llm","version":1,
                          "methods":["complete"],"name":"Language model","reason":"Asks a model."}],
                        "permissions":{"exec":[$entry]}}""",
                )
            }
        }
    }
}
