package org.evomaster.core.problem.asyncapi.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.evomaster.core.problem.api.schema.SchemaLocation
import org.evomaster.core.problem.api.schema.SchemaYamlUtils
import org.evomaster.core.remote.SutProblemException

/**
 * Turns the text of an AsyncAPI 3.x document into an [AsyncApiSchema].
 *
 * This is a purpose-built parser rather than a complete implementation of the specification.
 * It reads what EvoMaster acts on and ignores the rest; a keyword it does not know is simply
 * not read, and is never an error.
 *
 * Failures are graded. A document that cannot be read at all, is of the wrong version, or is
 * not an AsyncAPI document raises a [SutProblemException]. Anything narrower -- one broken
 * message, one unresolvable reference, one payload in a format that is not JSON Schema -- is
 * recorded in [AsyncApiSchema.warnings] and costs only the element it affects.
 */
object AsyncApiParser {

    /**
     * Schema formats that are JSON Schema by another name, and so can be read here. Compared
     * as prefixes, since the format string carries a version suffix.
     *
     * Anything else -- Avro and Protobuf being the ones that actually turn up -- describes a
     * payload in a language this parser does not speak, and the message is dropped.
     */
    private val JSON_SCHEMA_FORMATS = listOf(
        "application/vnd.aai.asyncapi",
        "application/schema+json",
        "application/schema+yaml",
        "application/vnd.oai.openapi"
    )

    fun parse(schemaText: String, location: SchemaLocation): AsyncApiSchema {

        val root = try {
            SchemaYamlUtils.readTree(schemaText)
        } catch (e: Exception) {
            throw SutProblemException("Failed to parse the AsyncAPI document: ${e.message}")
        }

        if (!root.isObject) {
            throw SutProblemException("The AsyncAPI document is not a JSON/YAML object")
        }

        val version = scalarOf(root.get("asyncapi"))
            ?: throw SutProblemException(
                "The document has no 'asyncapi' field, so it is not an AsyncAPI document." +
                        " If this is an OpenAPI schema, use the REST problem type instead."
            )

        if (!version.startsWith("3.")) {
            throw SutProblemException(
                "AsyncAPI version '$version' is not supported. Only 3.x is handled at the moment;" +
                        " in particular 2.x has no first-class reply, and is not parsed yet."
            )
        }

        val warnings = mutableListOf<String>()

        val defaultContentType = scalarOf(root.get("defaultContentType"))
            ?: AsyncApiSchema.DEFAULT_CONTENT_TYPE

        val componentSchemas = linkedMapOf<String, JsonNode>()
        objectFieldsOf(root.get("components")?.get("schemas")).forEach { (key, node) ->
            schemaOf(node, "the component schema '$key'", warnings)?.let { componentSchemas[key] = it }
        }

        val messages = linkedMapOf<String, AsyncApiMessage>()
        objectFieldsOf(root.get("components")?.get("messages")).forEach { (key, node) ->
            parseMessage(key, node, root, defaultContentType, componentSchemas, warnings)
                ?.let { messages[key] = it }
        }

        return AsyncApiSchema(
            rawText = schemaText,
            sourceLocation = location,
            version = version,
            defaultContentType = defaultContentType,
            messages = messages,
            componentSchemas = componentSchemas,
            warnings = warnings
        )
    }

    // ------------------------------------------------------------------ messages

    private fun parseMessage(
        id: String,
        rawNode: JsonNode,
        root: JsonNode,
        defaultContentType: String,
        componentSchemas: Map<String, JsonNode>,
        warnings: MutableList<String>
    ): AsyncApiMessage? {

        val declared = dereference(rawNode, root, warnings) ?: return null
        val node = applyTraits(declared, root, "messageTraits", warnings)

        val payload = schemaOf(node.get("payload"), "message '$id'", warnings)
            ?.takeUnless {
                reportUnfollowable(
                    it, componentSchemas, "message '$id'", "The message is ignored.", warnings
                )
            }

        if (payload == null && node.has("payload")) {
            //nothing can be built from a payload that cannot be read, so the message goes too
            return null
        }

        //broken headers cost only the headers: the message itself is still usable
        val headers = schemaOf(node.get("headers"), "the headers of message '$id'", warnings)
            ?.takeUnless {
                reportUnfollowable(
                    it, componentSchemas, "the headers of message '$id'",
                    "The headers are ignored.", warnings
                )
            }

        val bindings = dereferencedFields(node.get("bindings"), root, warnings)

        return AsyncApiMessage(
            id = id,
            name = scalarOf(node.get("name")) ?: id,
            contentType = scalarOf(node.get("contentType")) ?: defaultContentType,
            payload = payload,
            headers = headers,
            correlationId = parseCorrelationId(node.get("correlationId"), root, id, warnings),
            kafkaKey = bindings["kafka"]?.get("key"),
            bindings = bindings,
            examples = node.get("examples")?.filter { it.isObject }?.toList() ?: listOf(),
            title = scalarOf(node.get("title")),
            summary = scalarOf(node.get("summary")),
            description = scalarOf(node.get("description"))
        )
    }

    /**
     * Read a schema declaration as JSON Schema.
     *
     * The specification allows a schema to be wrapped in a "multi format" object stating the
     * language it is written in. When that language is not JSON Schema -- Avro, typically --
     * there is nothing useful to be done with it here, so null is returned and the caller
     * drops whatever depended on it.
     */
    private fun schemaOf(node: JsonNode?, owner: String, warnings: MutableList<String>): JsonNode? {

        if (node == null || node.isNull || !node.isObject) {
            return null
        }

        val format = scalarOf(node.get("schemaFormat")) ?: return node

        if (JSON_SCHEMA_FORMATS.none { format.startsWith(it) }) {
            warnings.add(
                "The schema of $owner is written in '$format', which is not JSON Schema." +
                        " It is ignored."
            )
            return null
        }

        //a multi-format declaration keeps the schema itself one level down
        return node.get("schema") ?: node
    }

    /**
     * The first reference under [start] that leads nowhere, or null when all of them lead
     * somewhere.
     *
     * References inside a schema are deliberately left unresolved for a later step to follow,
     * which only works if every one of them can in fact be followed: it must be local, of the
     * `#/components/schemas/...` form, and name a schema that is present. So the check has to
     * be transitive -- a payload may reference a perfectly good schema whose own properties
     * reference one that was dropped for being written in Avro, and following that chain is
     * the only way to notice.
     *
     * Schemas may reference each other in a cycle quite legitimately, hence the visited set.
     */
    private fun unfollowableSchemaRef(start: JsonNode, componentSchemas: Map<String, JsonNode>): String? {

        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<JsonNode>()
        pending.add(start)

        while (pending.isNotEmpty()) {

            AsyncApiRefResolver.collectRefs(pending.removeFirst()).forEach { ref ->

                //anything outside this document cannot be followed later
                if (!AsyncApiRefResolver.isLocal(ref)) {
                    return ref
                }

                val key = AsyncApiRefResolver.schemaKeyOf(ref) ?: return ref
                val target = componentSchemas[key] ?: return ref

                if (visited.add(key)) {
                    pending.add(target)
                }
            }
        }

        return null
    }

    /**
     * Report a schema whose references cannot all be followed, naming what depends on it.
     */
    private fun reportUnfollowable(
        schema: JsonNode,
        componentSchemas: Map<String, JsonNode>,
        owner: String,
        consequence: String,
        warnings: MutableList<String>
    ): Boolean {

        val ref = unfollowableSchemaRef(schema, componentSchemas) ?: return false

        warnings.add(
            "The schema of $owner refers to '$ref', which is not declared or could not be read." +
                    " $consequence"
        )
        return true
    }

    private fun parseCorrelationId(
        rawNode: JsonNode?,
        root: JsonNode,
        messageId: String,
        warnings: MutableList<String>
    ): AsyncApiCorrelationId? {

        if (rawNode == null || rawNode.isNull) {
            return null
        }

        val node = dereference(rawNode, root, warnings) ?: return null

        val location = scalarOf(node.get("location"))

        if (location == null) {
            warnings.add(
                "Message '$messageId' declares a correlationId with no 'location', so there is no" +
                        " way to know where the id travels"
            )
            return null
        }

        val parsed = AsyncApiCorrelationId.parse(location, scalarOf(node.get("description")))

        if (parsed == null) {
            warnings.add(
                "Message '$messageId' declares the correlation id at '$location', which is not a" +
                        " supported runtime expression. It must point inside the message header" +
                        " or payload."
            )
        }

        return parsed
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Follow a `$ref`, if the node is one. Returns null when it cannot be followed, having
     * said so.
     *
     * A reference may point at another reference, so this recurses -- and documents do contain
     * reference cycles, by mistake or through a generator, so [seen] stops it going round for
     * ever. Without it a two-entry cycle takes down the whole run with a StackOverflowError.
     */
    private fun dereference(
        node: JsonNode,
        root: JsonNode,
        warnings: MutableList<String>,
        seen: MutableSet<String> = mutableSetOf()
    ): JsonNode? {

        val ref = AsyncApiRefResolver.refOf(node) ?: return node

        if (!seen.add(ref)) {
            warnings.add("Reference '$ref' is part of a cycle of references, and cannot be followed")
            return null
        }

        val resolved = AsyncApiRefResolver.resolveLocal(root, ref)

        if (resolved == null) {
            warnings.add("Could not resolve reference '$ref'")
            return null
        }

        return if (AsyncApiRefResolver.refOf(resolved) != null) {
            dereference(resolved, root, warnings, seen)
        } else {
            resolved
        }
    }

    /**
     * Every field of a node, with any `$ref` followed -- both one on the node itself and one on
     * each of its values. Bindings are routinely shared through `components`, and reading them
     * without following the reference is worse than not reading them at all.
     */
    private fun dereferencedFields(
        node: JsonNode?,
        root: JsonNode,
        warnings: MutableList<String>
    ): Map<String, JsonNode> {

        if (node == null) {
            return mapOf()
        }

        val resolved = dereference(node, root, warnings) ?: return mapOf()

        return objectFieldsOf(resolved).mapValues { (_, value) ->
            dereference(value, root, warnings) ?: value
        }
    }

    /**
     * Fold a `traits` array into the object that declares it.
     *
     * Traits are a way of writing shared boilerplate once. They are merged shallowly and in
     * declaration order, and whatever the object states itself always wins, which is what the
     * specification prescribes.
     */
    private fun applyTraits(
        node: JsonNode,
        root: JsonNode,
        componentKind: String,
        warnings: MutableList<String>
    ): JsonNode {

        val traits = node.get("traits")

        if (traits == null || !traits.isArray || traits.isEmpty) {
            return node
        }

        val resolved = traits.mapNotNull { trait ->
            when (val target = dereference(trait, root, warnings)) {
                null -> {
                    //dereference has already said why
                    warnings.add("A trait declared in $componentKind could not be resolved, and is ignored")
                    null
                }
                else -> target.takeIf { it.isObject }
                    ?: run {
                        warnings.add("A trait declared in $componentKind is not an object, and is ignored")
                        null
                    }
            }
        }

        //the object's own fields go last, so that what it states itself always wins
        return shallowMerge(*resolved.toTypedArray(), node)
    }

    /**
     * The given objects merged one field deep, later ones winning, `$ref` dropped throughout.
     *
     * The reference has already been followed by the time anything is merged, so carrying it
     * into the result would only make a later reader follow it again and discard everything
     * that was merged on top.
     */
    private fun shallowMerge(vararg sources: JsonNode): JsonNode {

        val merged = JsonNodeFactory.instance.objectNode()

        sources.forEach { source ->
            source.fields().forEach { (key, value) ->
                if (key != "\$ref") {
                    merged.set<JsonNode>(key, value)
                }
            }
        }

        return merged
    }

    /**
     * The value of a scalar field as text, or null when it is absent, explicitly null, blank,
     * or not a scalar. Numbers and booleans are rendered as written, which matters for fields
     * that are free text but often look numeric.
     */
    private fun scalarOf(node: JsonNode?): String? {
        if (node == null || node.isNull || node.isContainerNode) {
            return null
        }
        return node.asText().takeIf { it.isNotBlank() }
    }

    /**
     * The fields of an object node, in declaration order. Empty when it is absent or is not an
     * object, so that callers never have to check first.
     */
    private fun objectFieldsOf(node: JsonNode?): Map<String, JsonNode> {

        if (node == null || !node.isObject) {
            return mapOf()
        }

        val map = linkedMapOf<String, JsonNode>()
        node.fields().forEach { (key, value) -> map[key] = value }
        return map
    }
}
