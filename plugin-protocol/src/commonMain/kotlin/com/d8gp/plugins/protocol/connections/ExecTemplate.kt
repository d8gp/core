package com.d8gp.plugins.protocol.connections

import com.d8gp.plugins.protocol.ExecArg
import com.d8gp.plugins.protocol.ExecDecl
import com.d8gp.plugins.protocol.ProtocolJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class ExecTemplateException(val code: String, val slot: String?) :
    Exception(if (slot == null) "exec slots: $code" else "exec slot $slot: $code") {
    companion object {
        const val SLOTS_NOT_OBJECT = "slots-not-object"
        const val SLOT_UNKNOWN = "slot-unknown"
        const val SLOT_MISSING = "slot-missing"
        const val SLOT_NOT_STRING = "slot-not-string"
        const val SLOT_NUL = "slot-nul"
        const val SLOT_LONE_SURROGATE = "slot-lone-surrogate"
        const val SLOT_NOT_IN_ENUM = "slot-not-in-enum"
        const val SLOT_TOO_LARGE = "slot-too-large"
    }
}

object ExecTemplate {
    const val DEFAULT_SLOT_MAX_BYTES = 4096

    // Without the program path. The broker reports every
    // ExecTemplateException code as invalid-request.
    fun build(decl: ExecDecl, slots: JsonElement?): List<String> {
        if (slots !is JsonObject) throw ExecTemplateException(ExecTemplateException.SLOTS_NOT_OBJECT, slot = null)
        val declared = decl.argv.filterIsInstance<ExecArg.Slot>().map { it.slot }.toSet()
        slots.keys.firstOrNull { it !in declared }?.let {
            throw ExecTemplateException(ExecTemplateException.SLOT_UNKNOWN, it)
        }
        return decl.argv.map { arg ->
            when (arg) {
                is ExecArg.Literal -> arg.value
                is ExecArg.Slot -> "${arg.flag}=${slotValue(arg, slots)}"
            }
        }
    }

    // Named after the grant field it fills. It covers the WHOLE declaration
    // (purpose, stdin and the limits too), so a grant is void after any change
    // to what the user approved, not only to the argv.
    fun argvDigest(decl: ExecDecl): String =
        CanonicalJson.sha256Hex(ProtocolJson.encodeToJsonElement(ExecDecl.serializer(), decl))

    private fun slotValue(arg: ExecArg.Slot, slots: JsonObject): String {
        fun fail(code: String): Nothing = throw ExecTemplateException(code, arg.slot)

        val raw = slots[arg.slot] ?: fail(ExecTemplateException.SLOT_MISSING)
        val value = raw.stringOrNull() ?: fail(ExecTemplateException.SLOT_NOT_STRING)
        if ('\u0000' in value) fail(ExecTemplateException.SLOT_NUL)
        if (hasLoneSurrogate(value)) fail(ExecTemplateException.SLOT_LONE_SURROGATE)
        if (arg.enum != null && value !in arg.enum) fail(ExecTemplateException.SLOT_NOT_IN_ENUM)

        val maxBytes = arg.maxBytes ?: DEFAULT_SLOT_MAX_BYTES.takeIf { arg.enum == null }
        if (maxBytes != null && utf8Length(value) > maxBytes) fail(ExecTemplateException.SLOT_TOO_LARGE)
        return value
    }
}
