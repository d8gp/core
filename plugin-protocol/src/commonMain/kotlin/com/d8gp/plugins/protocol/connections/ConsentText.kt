package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.EXEC_EGRESS_PREFIX
import com.d8gp.plugins.protocol.ExecArg
import com.d8gp.plugins.protocol.ExecDecl
import kotlinx.serialization.json.JsonArray

// A program as the HOST resolved it. Nothing here comes from the plugin: the
// host writes every sentence that describes what a program does.
data class ProgramFacts(
    val binary: String,
    val path: String,
    val signedBy: String? = null,
    val contacts: String? = null,
)

// What the consent text needs beyond the record and the graph. Names and the
// reason are the plugins' own claims and are labelled as such.
data class ConsentContext(
    val consumerName: String,
    val providerName: String,
    val reason: String? = null,
    val consumerProvides: List<String> = emptyList(), // interface ids
    val programs: List<ProgramFacts> = emptyList(),
    val providerCap: ProviderCap? = null,
    val usageIsEstimated: Boolean = false,
    val credentialNote: String? = null,
    val requestedFrom: String = "cli",
)

data class SignerFacts(
    val name: String,
    val teamId: String,
    val identifier: String,
)

data class ExecGrantRequest(
    val pluginName: String,
    val pluginId: String,
    val pluginVersion: String,
    val decl: ExecDecl,
    val launchPath: String,
    val resolvedPath: String? = null,
    val signer: SignerFacts? = null,
    val unsignedSha256: String? = null,
    val lockdownCheck: String? = null,
)

// The text whose hash is stored as consent. The manifest validator refuses
// the characters that could forge a line, but records, graphs and host facts
// reach this text without passing it, so every value is written by shown()
// and no value can pose as a line the host wrote.
object ConsentText {
    private const val LABEL_WIDTH = 15
    private const val GRANT_LABEL_WIDTH = 14
    private const val COMMAND_LINE_WIDTH = 96
    private const val KIBIBYTE = 1024L
    private val INDENT = " ".repeat(2 + LABEL_WIDTH)

    fun render(record: ConnectionRecord, graph: EgressGraph, context: ConsentContext): String {
        val consumer = record.consumer
        val provider = record.provider
        val lines = mutableListOf<String>()

        lines += "Connect two plugins"
        lines += ""
        lines += "  ${quoted(context.consumerName)} (${party(consumer.plugin, consumer.version)})" +
            "   [name declared by the plugin, not verified]"
        lines += "      will be able to call"
        lines += "  ${quoted(context.providerName)} (${party(provider.plugin, provider.version)})"
        lines += ""
        lines += field("Interface", "${shown(record.iface)} version ${record.major}, ${methodList(record.methods)}")
        if (context.reason != null) lines += field("It says why", quoted(context.reason))
        lines += dataLines(record)
        lines += providerEgressLines(record, context)
        lines += consumerEgressLines(record, graph, context)
        lines += limitLines(record.budget, context)
        context.providerCap?.let {
            lines += field("Provider total", "${it.requestsPerDay} requests/day across all plugins that use it.")
        }
        lines += field(
            "Credentials",
            listOfNotNull("none cross this connection.", context.credentialNote?.let(::shown)).joinToString(" "),
        )
        lines += "  This provider already serves: ${alreadyServes(record, graph)}."
        lines += "  Requested from: ${transportName(context.requestedFrom)}.   " +
            "You can disconnect at any time; calls in progress are cancelled."
        return lines.joinToString("\n")
    }

    fun renderExecGrant(request: ExecGrantRequest): String {
        val decl = request.decl
        val slots = decl.argv.filterIsInstance<ExecArg.Slot>()
        val lines = mutableListOf<String>()

        lines += "Allow ${quoted(request.pluginName)} (${party(request.pluginId, request.pluginVersion)}) " +
            "to run a program on this Mac?"
        lines += ""
        lines += grantField("Program", shown(request.launchPath))
        if (request.resolvedPath != null && request.resolvedPath != request.launchPath) {
            lines += grantField("", "-> ${shown(request.resolvedPath)}")
        }
        lines += grantField("Signed by", signerText(request))
        commandLine(decl).forEachIndexed { i, line ->
            lines += grantField(if (i == 0) "Command line" else "", line)
        }
        lines += "The plugin fills in: ${fillIns(slots, decl)}."
        lines += "Lockdown check: ${request.lockdownCheck?.let(::shown) ?: "not available for this program"}."
        lines += ""
        lines += "This program runs as you, outside the sandbox. It can read your files and use the network."
        lines += "d8gp limits how it is started, not what it does. Allow this only for a plugin you fully trust."
        lines += "Runs with: a clean environment, an empty temporary folder, ${timeLimitSeconds(decl)} s limit, " +
            "one run per request, every run recorded."
        lines += "This approval covers exactly this version of the plugin."
        return lines.joinToString("\n")
    }

    // A backslash is doubled so that text spelling an escape differs from a
    // character that was escaped.
    private fun shown(text: String): String = buildString {
        text.forEachIndexed { i, unit ->
            when {
                unit == '\\' -> append("\\\\")
                isForbiddenInConsentText(text, i) ->
                    append("\\u").append(unit.code.toString(16).padStart(4, '0'))
                else -> append(unit)
            }
        }
    }

    private fun isSpace(unit: Char): Boolean = when (unit.code) {
        0x20, 0xa0, 0x1680, in 0x2000..0x200a, 0x202f, 0x205f, 0x3000 -> true
        else -> false
    }

    private fun quoted(text: String): String = "\"" + shown(text).replace("\"", "\\\"") + "\""

    // One argv element or enum value. Quoted when it is empty or holds a
    // space, so that one element can never read as two.
    private fun word(text: String): String =
        if (text.isEmpty() || text.any(::isSpace)) quoted(text) else shown(text)

    private fun party(plugin: String, version: String?): String =
        if (version == null) shown(plugin) else "${shown(plugin)} ${shown(version)}"

    private fun field(label: String, value: String): String = "  ${label.padEnd(LABEL_WIDTH)}$value"

    private fun grantField(label: String, value: String): String = "${label.padEnd(GRANT_LABEL_WIDTH)}$value"

    private fun methodList(methods: List<String>): String =
        (if (methods.size == 1) "method: " else "methods: ") + methods.joinToString(", ", transform = ::shown)

    private fun orNone(values: List<String>): String =
        if (values.isEmpty()) "none" else values.joinToString(", ", transform = ::shown)

    private fun dataLines(record: ConnectionRecord): List<String> {
        val consumer = shown(record.consumer.plugin)
        val provider = shown(record.provider.plugin)
        return when (InterfaceSpecs.find(record.iface, record.major)?.dataFlow) {
            DataFlow.RequestBearing ->
                listOf(field("What is sent", "text chosen by $consumer, which may include your messages"))
            DataFlow.ResponseBearing ->
                listOf(field("What is read", "data held by $provider, returned to $consumer${scopeText(record)}"))
            null -> listOf(
                field("What is sent", "data chosen by $consumer"),
                field("What is read", "data chosen by $provider"),
            )
        }
    }

    private fun scopeText(record: ConnectionRecord): String {
        val sources = sourcesOf(record.scope["sources"] as? JsonArray)
        return if (sources.isEmpty()) "" else " (${orNone(sources)})"
    }

    private fun sourcesOf(sources: JsonArray?): List<String> = sources.orEmpty().mapNotNull { it.stringOrNull() }

    private fun providerEgressLines(record: ConnectionRecord, context: ConsentContext): List<String> {
        val egress = record.provider.egress
        val hosts = egress.filterNot { it.startsWith(EXEC_EGRESS_PREFIX) }
        val binaries = egress.filter { it.startsWith(EXEC_EGRESS_PREFIX) }.map { it.removePrefix(EXEC_EGRESS_PREFIX) }

        val lines = mutableListOf("  Where ${shown(record.provider.plugin)} can send it")
        if (binaries.isEmpty()) {
            lines += "${INDENT}network hosts: ${orNone(hosts)}."
            return lines
        }
        for (binary in binaries) {
            val facts = context.programs.firstOrNull { it.binary == binary }
            lines += "${INDENT}runs the program ${shown(binary)}${programDetail(facts)},"
            lines += "${INDENT}which contacts ${facts?.contacts?.let(::shown) ?: "servers d8gp does not know"}."
        }
        val program = if (binaries.size == 1) "that program connects" else "those programs connect"
        lines += "${INDENT}d8gp cannot restrict where $program. Declared network hosts: ${orNone(hosts)}."
        return lines
    }

    private fun programDetail(facts: ProgramFacts?): String = when {
        facts == null -> " (not approved yet)"
        facts.signedBy == null -> " (${shown(facts.path)}, not signed)"
        else -> " (${shown(facts.path)}, signed by ${shown(facts.signedBy)})"
    }

    private fun consumerEgressLines(
        record: ConnectionRecord,
        graph: EgressGraph,
        context: ConsentContext,
    ): List<String> {
        val consumer = record.consumer
        val active = graph.connections.filter { it.state == ConnectionStates.ACTIVE && it.id != record.id }

        val others = active.filter { it.consumer == consumer.plugin }.map { other ->
            val sources = sourcesOf(other.scope?.get("sources") as? JsonArray)
            "${shown(other.iface)} from ${shown(other.provider)}" +
                if (sources.isEmpty()) "" else " (${orNone(sources)})"
        }
        val readers = context.consumerProvides.map { iface ->
            val names = active.filter { it.provider == consumer.plugin && it.iface == iface }
                .map { it.consumer }
                .distinct()
            "its ${shown(iface.substringAfterLast('.'))} are read by: " +
                if (names.isEmpty()) "nobody yet" else orNone(names)
        }

        var own = "network hosts: ${orNone(consumer.net)}."
        if (consumer.exec.isNotEmpty()) own += " Programs: ${orNone(consumer.exec)}."
        val first = "$own Other connections: ${orNone(others)}" + if (readers.isEmpty()) "." else ";"

        val lines = mutableListOf("  Where ${shown(consumer.plugin)} can send what it receives", "$INDENT$first")
        readers.forEachIndexed { i, reader ->
            lines += "$INDENT$reader" + if (i == readers.lastIndex) "." else ";"
        }
        return lines
    }

    private fun limitLines(budget: Budget, context: ConsentContext): List<String> {
        val enforcement = if (context.usageIsEstimated) {
            "Token totals for this provider are estimates; request counts and time are enforced."
        } else {
            "Token totals are reported by the provider; request counts and time are enforced."
        }
        return listOf(
            field(
                "Limits",
                "${budget.requestsPerMinute} requests/min, ${budget.requestsPerDay} requests/day, " +
                    "${budget.maxTokensPerRequest} output tokens and ${byteSize(budget.maxInputBytes)} " +
                    "input per request,",
            ),
            "${INDENT}model tier: ${shown(budget.maxTier)}, ${budget.maxCallMs / 1000} s per call, " +
                "${budget.maxConcurrent} at a time.",
            "$INDENT$enforcement",
        )
    }

    private fun byteSize(bytes: Long): String =
        if (bytes % KIBIBYTE == 0L) "${bytes / KIBIBYTE} KiB" else "$bytes bytes"

    private fun alreadyServes(record: ConnectionRecord, graph: EgressGraph): String {
        val consumers = graph.connections
            .filter { it.state == ConnectionStates.ACTIVE && it.id != record.id }
            .filter { it.provider == record.provider.plugin && it.consumer != record.consumer.plugin }
            .map { it.consumer }
            .distinct()
        return if (consumers.isEmpty()) "nobody else" else orNone(consumers)
    }

    private fun transportName(requestedFrom: String): String = when (requestedFrom) {
        "cli" -> "command line"
        "ui" -> "app window"
        "socket" -> "control socket"
        else -> shown(requestedFrom)
    }

    private fun signerText(request: ExecGrantRequest): String {
        val signer = request.signer
        return when {
            signer != null -> "${shown(signer.name)} (${shown(signer.teamId)}), ${shown(signer.identifier)}"
            request.unsignedSha256 != null -> "nobody. Pinned by SHA-256 ${shown(request.unsignedSha256)}"
            else -> "nobody"
        }
    }

    private fun commandLine(decl: ExecDecl): List<String> {
        val words = listOf(shown(decl.binary)) + decl.argv.map { arg ->
            when (arg) {
                is ExecArg.Literal -> word(arg.value)
                is ExecArg.Slot -> "${shown(arg.flag)}=<${shown(arg.slot)}>"
            }
        }
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            when {
                line.isEmpty() -> line = word
                line.length + 1 + word.length <= COMMAND_LINE_WIDTH -> line += " $word"
                else -> {
                    lines += line
                    line = word
                }
            }
        }
        lines += line
        return lines
    }

    private fun fillIns(slots: List<ExecArg.Slot>, decl: ExecDecl): String {
        val parts = slots.map { "${shown(it.slot)} (${it.enum?.map(::word)?.let(::alternatives) ?: "text"})" }
        val input = "the program's input".takeIf { decl.stdin != null }
        return when {
            parts.isEmpty() -> input ?: "nothing"
            input == null -> parts.joinToString(", ")
            else -> parts.joinToString(", ") + ", and $input"
        }
    }

    private fun alternatives(values: List<String>): String =
        if (values.size < 2) values.joinToString() else values.dropLast(1).joinToString(", ") + " or " + values.last()

    private fun timeLimitSeconds(decl: ExecDecl): Long = minOf(decl.timeoutMs ?: MAX_CALL_MS, MAX_CALL_MS) / 1000
}
