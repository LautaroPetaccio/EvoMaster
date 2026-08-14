package org.evomaster.core.problem.asyncapi.schema

import com.fasterxml.jackson.databind.JsonNode
import org.evomaster.core.problem.rest.schema.SchemaLocation

/**
 * A parsed AsyncAPI 3.x document, normalised so that the rest of EvoMaster never has to walk
 * the raw YAML/JSON tree.
 *
 * At this stage the model covers the message layer: what a service can be sent and what it can
 * send, and the JSON Schema of each. Channels and operations, which say where those messages
 * travel and which of them a given interaction uses, are a separate step.
 *
 * "Normalised" means two things:
 *
 * 1. all `$ref` between AsyncAPI constructs (messages, correlation ids, traits) are already
 *    followed, and are represented as plain keys;
 * 2. all `$ref` *inside* a message payload / headers JSON Schema are instead left verbatim, in
 *    the local `#/components/schemas/<key>` form, and every schema they can reach is in
 *    [componentSchemas]. A message whose schema reaches a reference that cannot be followed is
 *    dropped rather than handed on, so whatever builds genes later may resolve any reference it
 *    meets against [componentSchemas] alone.
 *
 * Parsing is deliberately lenient: anything that only makes a single element unusable is
 * reported in [warnings] rather than raised, so one exotic message cannot make a whole
 * document untestable.
 */
class AsyncApiSchema(

    /**
     * The document exactly as retrieved, before any parsing, as
     * [org.evomaster.core.problem.rest.schema.SchemaOpenAPI] keeps it for OpenAPI. What needs
     * the original text is reporting a problem in terms the user can find in their file.
     */
    val rawText: String,

    /**
     * Where [rawText] came from.
     */
    val sourceLocation: SchemaLocation,

    /**
     * Value of the root `asyncapi` field, e.g. "3.0.0".
     */
    val version: String,

    /**
     * Root `defaultContentType`, used by any message not declaring its own `contentType`.
     * Defaults to "application/json" when the document declares none.
     */
    val defaultContentType: String,

    /**
     * Message id -> message, from `components.messages`.
     */
    val messages: Map<String, AsyncApiMessage>,

    /**
     * Schema key -> the raw JSON Schema node, as declared under `components.schemas`.
     */
    val componentSchemas: Map<String, JsonNode>,

    /**
     * Everything that went wrong without being fatal: a reference that could not be resolved, a
     * payload in a format we cannot read. Reported to the user, so that a surprising result can
     * be traced back to the document.
     */
    val warnings: List<String>
) {

    companion object {
        const val DEFAULT_CONTENT_TYPE = "application/json"
    }
}
