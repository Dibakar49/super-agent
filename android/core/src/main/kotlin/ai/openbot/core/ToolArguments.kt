package ai.openbot.core

import kotlinx.serialization.json.*

/** Validate the JSON types before dispatch; strings such as "false" must never become booleans. */
fun validateToolArguments(
    tool: AgentTool,
    args: JsonObject,
) {
    fun validate(
        schema: JsonObject,
        value: JsonElement,
        path: String,
    ) {
        val typeValue = schema["type"]
        val types =
            when (typeValue) {
                is JsonArray -> typeValue.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(typeValue.content)
                else -> emptyList()
            }
        val valid =
            types.isEmpty() ||
                types.any { type ->
                    when (type) {
                        "object" -> value is JsonObject
                        "array" -> value is JsonArray
                        "string" -> value is JsonPrimitive && value.isString
                        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                        "null" -> value == JsonNull
                        else -> false
                    }
                }
        require(valid) { "$path must be ${types.joinToString(" or ")}." }
        if (value is JsonObject) {
            val properties = (schema["properties"] as? JsonObject).orEmpty()
            (schema["required"] as? JsonArray).orEmpty().forEach { field -> require(value.containsKey(field.jsonPrimitive.content)) { "Provide ${field.jsonPrimitive.content}." } }
            if (schema["additionalProperties"] == JsonPrimitive(false))require(value.keys.all { it in properties }) { "$path contains an unknown field." }
            value.forEach { (name, item) -> (properties[name] as? JsonObject)?.let { validate(it, item, name) } }
        }
        if (value is JsonArray)(schema["items"] as? JsonObject)?.let { item -> value.forEachIndexed { index, entry -> validate(item, entry, "$path[$index]") } }
        if (value is JsonPrimitive && value.isString) {
            val length = value.content.length
            schema["minLength"]?.jsonPrimitive?.intOrNull?.let { require(length >= it) { "$path must contain at least $it characters." } }
            schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { require(length <= it) { "$path must contain at most $it characters." } }
        }
        (schema["enum"] as? JsonArray)?.let { require(value in it) { "Choose a supported value for $path." } }
    }
    validate(tool.schema, args, "Arguments")
}
