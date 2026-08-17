package org.evomaster.core.problem.asyncapi.builder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.webfuzzing.asyncapi.models.AsyncApiCorrelationId
import com.webfuzzing.asyncapi.models.AsyncApiDocument
import com.webfuzzing.asyncapi.models.AsyncApiMessage
import com.webfuzzing.asyncapi.resolver.AsyncApiRefResolver
import org.evomaster.core.EMConfig
import org.evomaster.core.problem.rest.builder.RestActionBuilderV3
import org.evomaster.core.search.gene.Gene

/**
 * Turns the JSON Schema of an AsyncAPI message into the genes the search mutates.
 *
 * The parser deliberately stops at the schema: it leaves every `$ref` inside a payload alone,
 * and guarantees that whatever those references reach is present in
 * [AsyncApiDocument.componentSchemas]. That guarantee is what this builder trades on -- it hands
 * the whole schema map to [RestActionBuilderV3.createGeneForDTO], which wraps it in a synthetic
 * OpenAPI document and lets the existing machinery resolve the references and build the genes.
 *
 * Reusing that machinery rather than writing a second JSON-Schema-to-gene converter means
 * AsyncAPI payloads get everything REST already has: numeric and length bounds, formats,
 * `oneOf` as a choice, cycle detection, and optional-versus-required fields.
 */
object AsyncApiGeneBuilder {

    /**
     * Under what name a payload written inline, rather than as a reference to a named schema,
     * is added to the schema map. The prefix keeps it from colliding with a declared schema.
     */
    private const val INLINE_PREFIX = "_asyncapi_"

    /**
     * The genes for a message's payload, or null when it declares none.
     */
    fun buildPayloadGene(
        schema: AsyncApiDocument,
        message: AsyncApiMessage,
        options: RestActionBuilderV3.Options
    ): Gene? = build(message.payload, "${message.id}.payload", schema, options)

    /**
     * The genes for a message's headers, or null when it declares none.
     *
     * Headers are built separately from the payload because they travel separately on the wire:
     * a transport with metadata puts them beside the body rather than in it.
     *
     * The header carrying the correlation id, where the message declares one, is left out. That
     * value is stamped fresh at each execution so that a reply can be paired with the request
     * that caused it, and a gene holding a value that is about to be overwritten is worse than
     * no gene at all: the search would spend mutations on something that never reaches the wire.
     *
     * Only an id declared one level deep is left out, which is how every document seen so far
     * writes it. One pointing further in -- `$message.header#/meta/id` -- keeps its gene, and
     * the search wastes a few mutations on a field that is overwritten before it is sent. That
     * is the mild failure of the two, and preferable to descending into a headers schema whose
     * shape is not known here.
     */
    fun buildHeadersGene(
        schema: AsyncApiDocument,
        message: AsyncApiMessage,
        options: RestActionBuilderV3.Options
    ): Gene? = build(withoutCorrelationId(message), "${message.id}.headers", schema, options)

    /**
     * The headers schema without the property the correlation id is stamped into.
     */
    private fun withoutCorrelationId(message: AsyncApiMessage): JsonNode? {

        val headers = message.headers ?: return null
        val correlation = message.correlationId

        if (correlation == null || correlation.source != AsyncApiCorrelationId.Source.HEADER) {
            return headers
        }

        val field = correlation.fieldName ?: return headers
        val properties = headers.get("properties")

        if (properties == null || !properties.has(field)) {
            return headers
        }

        val copy = headers.deepCopy<JsonNode>() as ObjectNode
        val kept = (copy.get("properties") as ObjectNode).apply { remove(field) }

        /*
            When the stamped id was the only header, there is nothing left to vary. Returning
            the empty schema would build a free-form map gene, which is worse than nothing: it
            would invite the search to invent headers the contract never declared.
         */
        if (kept.isEmpty) {
            return null
        }

        //a field that is no longer there cannot be required either
        (copy.get("required") as? ArrayNode)?.let { required ->
            val kept = required.filter { it.asText() != field }
            copy.remove("required")
            if (kept.isNotEmpty()) {
                copy.putArray("required").apply { kept.forEach { add(it) } }
            }
        }

        return copy
    }

    /**
     * Options for building AsyncAPI payloads.
     *
     * Note `invalidData = false`, which is not what REST does. That flag makes the builder add
     * a bogus "EVOMASTER" member to every enum, on purpose, to probe how a service handles a
     * value it never declared. In a message payload that backfires: an enum of one value is how
     * documents write a routing discriminator (`request: {const: list_legs}`, which arrives
     * here as an enum), and a message carrying a discriminator the service does not recognise
     * is silently dropped. Half the messages sent would then go nowhere -- wasting executions,
     * and firing the no-reply fault target for a fault of our own making.
     *
     * Nothing is lost for reaching error paths: the bounds that matter are preserved, so a
     * field declared `minimum: 3` is still fuzzed across its boundary.
     */
    fun options(config: EMConfig) = RestActionBuilderV3.Options(
        enableConstraintHandling = config.enableSchemaConstraintHandling,
        invalidData = false,
        usingWhiteBox = !config.blackBox,
        enableAdvancedFormats = config.enableAdvancedFormats,
        inferFormatFromNames = config.inferFormatFromNames
    )

    private fun build(
        declared: JsonNode?,
        inlineName: String,
        schema: AsyncApiDocument,
        options: RestActionBuilderV3.Options
    ): Gene? {

        if (declared == null) {
            return null
        }

        /*
            A payload that is just a reference to a declared schema is built under that schema's
            own name, which keeps the gene named after what the document calls it. Anything else
            is added to the map under a name of our own.
         */
        val referenced = AsyncApiRefResolver.refOf(declared)
            ?.let { AsyncApiRefResolver.schemaKeyOf(it) }
            ?.takeIf { schema.componentSchemas.containsKey(it) && isWholeSchemaRef(declared) }

        val name = referenced ?: (INLINE_PREFIX + inlineName)

        val schemas = JsonNodeFactory.instance.objectNode()
        schema.componentSchemas.forEach { (key, node) -> schemas.set<JsonNode>(key, usable(node)) }
        if (referenced == null) {
            schemas.set<JsonNode>(name, usable(declared))
        }

        //the format createGeneForDTO expects: the name of the wanted schema, then all of them
        return RestActionBuilderV3.createGeneForDTO(name, "\"$name\":$schemas", options)
    }

    /**
     * Whether the node is nothing but a reference, so that following it loses nothing.
     */
    private fun isWholeSchemaRef(node: JsonNode) =
        node.isObject && node.fieldNames().asSequence().toList() == listOf("\$ref")

    /**
     * A copy of the schema with the constructs the gene builder cannot read translated into
     * ones it can. The original is left alone, as it belongs to the parsed document.
     */
    private fun usable(node: JsonNode): JsonNode = rewriteConst(node.deepCopy())

    /**
     * Rewrite every `const` into whatever pins a field to that one value in a form the gene
     * builder reads.
     *
     * `const` is JSON Schema's way of fixing a field to one literal, and documents use it to
     * mark which message a payload is. The gene builder does not read it at all -- with or
     * without a `type` beside it, a `const` field becomes a free value, so the discriminator
     * would be random and the service would not recognise the message.
     *
     * Which rewrite is used depends on the kind of literal, because the two available ways of
     * saying "only this value" do not both work for every type:
     *
     * - **text** becomes a single-valued `enum`. Note the builder adds a bogus member to a
     *   string enum when asked for invalid data, which is why [options] turns that off.
     * - **numbers** become equal bounds instead. An enum would be read, but the bogus member
     *   added to a numeric enum is not conditional on anything, so an enum of one value would
     *   always come back as two. Equal bounds pin the value with no such surprise.
     * - **booleans** are left alone. There is no enum handling for them and no bounds to set;
     *   a two-valued field is guessed half the time anyway.
     */
    private fun rewriteConst(node: JsonNode): JsonNode {

        if (node.isArray) {
            node.forEach { rewriteConst(it) }
            return node
        }

        if (!node.isObject) {
            return node
        }

        val obj = node as ObjectNode
        val const = obj.get("const")

        if (const != null && !const.isContainerNode) {
            when {
                const.isTextual -> {
                    obj.remove("const")
                    obj.putArray("enum").add(const)
                    obj.put("type", "string")
                }
                const.isNumber -> {
                    obj.remove("const")
                    obj.set<JsonNode>("minimum", const)
                    obj.set<JsonNode>("maximum", const)
                    obj.put("type", if (const.isIntegralNumber) "integer" else "number")
                }
                const.isBoolean -> {
                    obj.remove("const")
                    obj.put("type", "boolean")
                }
            }
        }

        obj.fields().forEach { (_, value) -> rewriteConst(value) }

        return obj
    }
}
