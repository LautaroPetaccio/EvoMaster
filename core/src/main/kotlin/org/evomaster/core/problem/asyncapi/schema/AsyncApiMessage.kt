package org.evomaster.core.problem.asyncapi.schema

import com.fasterxml.jackson.databind.JsonNode

/**
 * One message definition, i.e. the shape of what travels on a channel.
 *
 * Messages declared under `components.messages` and messages written inline inside a channel
 * both end up here; the only difference is the [id] the latter get.
 */
data class AsyncApiMessage(

    /**
     * The key this message is registered under in [AsyncApiSchema.messages]: its component key
     * when it was declared under `components.messages`, or the synthetic
     * `<channelKey>.<localMessageKey>` when it was written inline in a channel.
     */
    val id: String,

    /**
     * The message's own `name` field, falling back to [id] when it declares none.
     */
    val name: String,

    /**
     * MIME type of the payload, falling back to the document's `defaultContentType`.
     */
    val contentType: String,

    /**
     * The payload's JSON Schema, as a raw node.
     *
     * Deliberately not resolved: any `$ref` in here is left in the local
     * `#/components/schemas/<key>` form, and every schema it can reach is in
     * [AsyncApiSchema.componentSchemas]. Null when the message declares no payload, or when
     * the payload is in a schema format that cannot be read as JSON Schema (in which case
     * there is a matching entry in [AsyncApiSchema.warnings]).
     */
    val payload: JsonNode? = null,

    /**
     * The JSON Schema of the message headers, kept separate from the payload exactly as the
     * specification keeps them. Same "raw node, unresolved refs" treatment as [payload].
     */
    val headers: JsonNode? = null,

    /**
     * Where the correlation id travels, when the message declares it.
     */
    val correlationId: AsyncApiCorrelationId? = null,

    /**
     * The JSON Schema of the Kafka message key, from `bindings.kafka.key`. Null when the
     * message declares no key binding, in which case the broker distributes records across
     * partitions itself.
     */
    val kafkaKey: JsonNode? = null,

    /**
     * Protocol bindings as declared, keyed by protocol name.
     */
    val bindings: Map<String, JsonNode> = mapOf(),

    /**
     * The message's `examples` entries, kept raw. Not interpreted here, but useful later as
     * seeds for the search.
     */
    val examples: List<JsonNode> = listOf(),

    val title: String? = null,

    val summary: String? = null,

    val description: String? = null
)

/**
 * A parsed `correlationId.location`, i.e. where in a message the value that pairs a request
 * with its reply is to be written and read.
 *
 * The specification writes it as a runtime expression, either `$message.header#/<pointer>` or
 * `$message.payload#/<pointer>`. It is parsed once, here, so that nothing downstream has to
 * take that string apart again.
 *
 * Which of the two is usable is a property of the transport, not of the document: AMQP and
 * Kafka can carry the id as metadata, while MQTT 3.1.1 and raw WebSocket have no metadata at
 * all and can only carry it inside the payload. A document may well declare a header location
 * on a transport that has no headers.
 */
data class AsyncApiCorrelationId(

    /**
     * The expression exactly as written, kept for error messages.
     */
    val raw: String,

    val source: Source,

    /**
     * The JSON Pointer part, e.g. "/correlationId" or "/request_id". Always starts with "/".
     */
    val pointer: String,

    val description: String? = null
) {

    /**
     * The name of the field the id lives in, for the common case of a pointer one level deep.
     * Null for a nested pointer, where callers have to walk [pointer] themselves.
     */
    val fieldName: String? = pointer.removePrefix("/")
        .split("/")
        .singleOrNull()
        ?.takeIf { it.isNotBlank() }
        //JSON Pointer escaping: a field whose name contains a slash writes it as '~1'
        ?.replace("~1", "/")
        ?.replace("~0", "~")

    enum class Source { HEADER, PAYLOAD }

    companion object {

        private const val HEADER_PREFIX = "\$message.header#"

        private const val PAYLOAD_PREFIX = "\$message.payload#"

        /**
         * Parse a `correlationId.location` runtime expression, or return null if it is not one
         * of the two forms the specification defines.
         */
        fun parse(location: String, description: String? = null): AsyncApiCorrelationId? {

            val trimmed = location.trim()

            val source = when {
                trimmed.startsWith(HEADER_PREFIX) -> Source.HEADER
                trimmed.startsWith(PAYLOAD_PREFIX) -> Source.PAYLOAD
                else -> return null
            }

            val prefix = if (source == Source.HEADER) HEADER_PREFIX else PAYLOAD_PREFIX
            val pointer = trimmed.substring(prefix.length)

            if (pointer.isBlank() || !pointer.startsWith("/")) {
                return null
            }

            return AsyncApiCorrelationId(trimmed, source, pointer, description)
        }
    }
}
