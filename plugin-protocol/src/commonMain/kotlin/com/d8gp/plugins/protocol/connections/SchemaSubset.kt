package com.d8gp.plugins.protocol.connections

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The JSON Schema dialect accepted for structured model output. It is small on
// purpose: every keyword is one the host can enforce itself, and none can
// reference anything outside the schema.
object SchemaSubset {
    const val MAX_DEPTH = 8
    const val MAX_NODES = 256
    const val MAX_BYTES = 32_768

    const val RULE_SCHEMA = "schema"
    const val RULE_DEPTH = "depth"
    const val RULE_NODES = "nodes"
    const val RULE_BYTES = "bytes"

    private val KEYWORDS = setOf(
        "type",
        "properties",
        "required",
        "items",
        "enum",
        "description",
        "additionalProperties",
    )
    private val TYPE_NAMES = setOf("string", "number", "integer", "boolean", "object", "array", "null")
    private val OBJECT_ONLY_KEYWORDS = listOf("additionalProperties", "properties", "required")

    // The id of the first broken rule (a keyword, or one of the RULE_ ids),
    // or null when the schema is inside the dialect.
    fun check(schema: JsonElement): String? {
        DialectWalk().visit(schema, depth = 1)?.let { return it }
        val bytes = try {
            utf8Length(CanonicalJson.encode(schema))
        } catch (_: CanonicalJsonException) {
            return RULE_SCHEMA
        }
        return if (bytes > MAX_BYTES) RULE_BYTES else null
    }

    fun isInDialect(schema: JsonElement): Boolean = check(schema) == null

    // Only meaningful for a schema that passed check().
    fun isValid(schema: JsonElement, instance: JsonElement): Boolean {
        val node = schema as? JsonObject ?: return false
        val types = typeNames(node["type"]) ?: return false
        if (types.none { matchesType(it, instance) }) return false

        val enum = node["enum"] as? JsonArray
        if (enum != null && enum.none { sameScalar(it, instance) }) return false

        return when (instance) {
            is JsonObject -> isValidObject(node, instance)
            is JsonArray -> {
                val items = node["items"] ?: return false
                instance.all { isValid(items, it) }
            }
            else -> true
        }
    }

    private fun isValidObject(node: JsonObject, instance: JsonObject): Boolean {
        val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (node["required"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }
        return instance.keys.all { it in properties } &&
            required.all { it in instance } &&
            instance.all { (key, value) -> isValid(properties.getValue(key), value) }
    }

    private fun typeNames(type: JsonElement?): List<String>? = when (type) {
        is JsonArray -> type.map { it.stringOrNull() ?: return null }
        else -> type.stringOrNull()?.let(::listOf)
    }

    private fun matchesType(type: String, instance: JsonElement): Boolean = when (type) {
        "null" -> instance is JsonNull
        "object" -> instance is JsonObject
        "array" -> instance is JsonArray
        "string" -> instance is JsonPrimitive && instance.isString
        "boolean" -> instance.isBoolean()
        "number" -> instance.numberOrNull() != null
        "integer" -> instance.numberOrNull()?.isIntegral() == true
        else -> false
    }

    private fun sameScalar(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a !is JsonPrimitive || b !is JsonPrimitive) return false
        if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
        if (a.isBoolean() || b.isBoolean()) return a.content == b.content
        return a.numberOrNull() == b.numberOrNull()
    }

    private class DialectWalk {
        private var nodes = 0

        fun visit(schema: JsonElement, depth: Int): String? {
            val node = schema as? JsonObject ?: return RULE_SCHEMA
            node.keys.sorted().firstOrNull { it !in KEYWORDS }?.let { return it }
            if (depth > MAX_DEPTH) return RULE_DEPTH
            nodes++
            if (nodes > MAX_NODES) return RULE_NODES

            val types = checkedTypes(node["type"]) ?: return "type"
            val description = node["description"]
            if (description != null && description.stringOrNull() == null) return "description"
            if (!enumFits(node["enum"], types)) return "enum"

            val properties = node["properties"]
            if ("object" in types) {
                val additional = node["additionalProperties"]
                if (additional !is JsonPrimitive || additional.isString || additional.content != "false") {
                    return "additionalProperties"
                }
                if (properties != null && properties !is JsonObject) return "properties"
                if (!requiredFits(node["required"], properties)) return "required"
            } else {
                OBJECT_ONLY_KEYWORDS.firstOrNull { it in node }?.let { return it }
            }

            val items = node["items"]
            if ("array" in types) {
                if (items !is JsonObject) return "items"
            } else if (items != null) {
                return "items"
            }

            if (properties is JsonObject && "object" in types) {
                for (key in properties.keys.sorted()) {
                    visit(properties.getValue(key), depth + 1)?.let { return it }
                }
            }
            if (items != null) visit(items, depth + 1)?.let { return it }
            return null
        }

        private fun checkedTypes(type: JsonElement?): List<String>? {
            val names = typeNames(type) ?: return null
            if (type is JsonArray && (names.isEmpty() || names.distinct().size != names.size)) return null
            return names.takeIf { it.all(TYPE_NAMES::contains) }
        }

        private fun enumFits(enum: JsonElement?, types: List<String>): Boolean {
            if (enum == null) return true
            val values = enum as? JsonArray ?: return false
            if (values.isEmpty()) return false
            return values.all { value ->
                val scalar = value is JsonNull || (value is JsonPrimitive &&
                    (value.isString || value.isBoolean() || value.safeIntegerOrNull() != null))
                scalar && types.any { matchesType(it, value) }
            }
        }

        private fun requiredFits(required: JsonElement?, properties: JsonObject?): Boolean {
            if (required == null) return true
            val names = (required as? JsonArray)?.map { it.stringOrNull() ?: return false } ?: return false
            return names.distinct().size == names.size && names.all { properties != null && it in properties }
        }
    }
}
