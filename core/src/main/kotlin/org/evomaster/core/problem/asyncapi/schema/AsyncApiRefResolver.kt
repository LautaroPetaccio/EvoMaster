package org.evomaster.core.problem.asyncapi.schema

import com.fasterxml.jackson.databind.JsonNode
import org.evomaster.core.problem.rest.schema.SchemaUtils
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * `$ref` handling for AsyncAPI documents.
 *
 * There are two quite different jobs here, and they are treated differently on purpose.
 *
 * References *between AsyncAPI constructs* (a message pointing at a trait, a message pointing
 * at a correlation id) are resolved by [resolveLocal] while parsing: the model that comes out
 * holds plain keys, never pointers.
 *
 * References *inside a JSON Schema* are not resolved at all, because whatever builds genes
 * from them later would rather resolve them itself. What is done instead is to make sure it
 * can: every schema a payload can reach has to sit in one flat map under
 * `#/components/schemas/`, which is what [schemaKeyOf] is used to check.
 */
object AsyncApiRefResolver {

    private const val REF = "\$ref"

    const val SCHEMA_PREFIX = "#/components/schemas/"

    fun isLocal(ref: String) = SchemaUtils.isLocalRef(ref)

    /**
     * Follow a local `$ref` such as `#/components/messages/foo` inside [root].
     * Returns null when the pointer does not lead anywhere.
     */
    fun resolveLocal(root: JsonNode, ref: String): JsonNode? {

        if (!isLocal(ref)) {
            return null
        }

        var current: JsonNode = root

        ref.removePrefix("#").split("/")
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                current = current.get(decodePointerSegment(segment)) ?: return null
            }

        return current
    }

    /**
     * The last segment of a `$ref`, i.e. the key of what it points at, but only if the pointer
     * has the shape we expect. `refKey("#/components/messages/foo", "#/components/messages/")`
     * gives "foo", while a pointer somewhere else gives null.
     */
    fun refKey(ref: String, expectedPrefix: String): String? {

        if (!ref.startsWith(expectedPrefix)) {
            return null
        }

        val key = ref.substring(expectedPrefix.length)

        //must be a single segment: a deeper pointer is something else than what was asked for
        return if (key.isNotBlank() && !key.contains("/")) decodePointerSegment(key) else null
    }

    fun refOf(node: JsonNode?): String? = node?.get(REF)?.takeIf { it.isTextual }?.asText()

    /**
     * The component schema a reference reaches into, or null if it addresses something else.
     *
     * Unlike [refKey] this accepts a pointer that goes deeper than the schema itself, such as
     * `#/components/schemas/Order/properties/item`: the schema named by the first segment is
     * still what has to be present for the pointer to lead anywhere.
     */
    fun schemaKeyOf(ref: String): String? {

        if (!ref.startsWith(SCHEMA_PREFIX)) {
            return null
        }

        val key = ref.substring(SCHEMA_PREFIX.length).substringBefore("/")

        return if (key.isBlank()) null else decodePointerSegment(key)
    }

    /**
     * Every `$ref` value under [node], at any depth.
     */
    fun collectRefs(node: JsonNode): List<String> {
        val refs = mutableListOf<String>()
        collectRefsInto(node, refs)
        return refs
    }

    private fun collectRefsInto(node: JsonNode, out: MutableList<String>) {
        if (node.isObject) {
            refOf(node)?.let { out.add(it) }
            node.fields().forEach { (_, value) -> collectRefsInto(value, out) }
        } else if (node.isArray) {
            node.forEach { collectRefsInto(it, out) }
        }
    }

    /**
     * JSON Pointer escaping: "~1" is a "/" and "~0" is a "~". Percent-encoding is undone too,
     * as references are URIs.
     */
    private fun decodePointerSegment(segment: String): String {
        //only when there is something to decode: URLDecoder would otherwise eat a literal '+'
        val decoded = if (segment.contains('%')) {
            try {
                URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
            } catch (e: Exception) {
                segment
            }
        } else {
            segment
        }
        return decoded.replace("~1", "/").replace("~0", "~")
    }
}
