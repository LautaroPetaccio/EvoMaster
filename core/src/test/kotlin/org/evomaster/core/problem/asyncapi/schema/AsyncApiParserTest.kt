package org.evomaster.core.problem.asyncapi.schema

import org.evomaster.core.remote.SutProblemException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for the AsyncAPI 3.x parser.
 *
 * Each document under `/asyncapi/artificial` is written to pin down one thing. Several of them
 * exist because a real published document did what they describe: a payload written in Avro, a
 * schema pointing at one that was dropped, references that go round in a circle.
 */
class AsyncApiParserTest {

    private fun load(resourcePath: String): AsyncApiSchema =
        AsyncApiAccess.getAsyncApiFromResource(resourcePath)

    // ------------------------------------------------------------------ the shape of a document

    @Test
    fun testMessagesAndTheirSchemas() {

        val schema = load("/asyncapi/artificial/messages.yaml")

        assertTrue(schema.warnings.isEmpty(), "unexpected warnings: ${schema.warnings}")
        assertEquals("3.0.0", schema.version)
        assertEquals("application/json", schema.defaultContentType)
        assertEquals(setOf("signupRequest", "signupReply", "heartbeat"), schema.messages.keys)
        assertEquals(setOf("SignupRequest", "Address"), schema.componentSchemas.keys)

        val request = schema.messages.getValue("signupRequest")
        assertEquals("SignupRequest", request.name)
        assertEquals("Sign a user up", request.title)
        //no contentType of its own, so the document's default applies
        assertEquals("application/json", request.contentType)
        //the payload keeps its reference rather than being inlined
        assertEquals("#/components/schemas/SignupRequest", request.payload!!.get("\$ref").asText())

        val reply = schema.messages.getValue("signupReply")
        assertEquals("application/vnd.example+json", reply.contentType)
        assertTrue(reply.payload!!.get("properties").has("userId"))

        //a message with no name of its own is known by its component key
        assertEquals("heartbeat", schema.messages.getValue("heartbeat").name)
    }

    @Test
    fun testJsonAndYamlAreParsedTheSameWay() {

        val fromYaml = load("/asyncapi/artificial/messages.yaml")
        val fromJson = load("/asyncapi/artificial/messages.json")

        //without this, two empty models would compare equal and prove nothing
        assertTrue(fromJson.messages.isNotEmpty())

        assertEquals(fromYaml.version, fromJson.version)
        assertEquals(fromYaml.defaultContentType, fromJson.defaultContentType)
        assertEquals(fromYaml.messages.keys, fromJson.messages.keys)
        assertEquals(fromYaml.componentSchemas.keys, fromJson.componentSchemas.keys)
        assertEquals(
            fromYaml.messages.getValue("signupRequest").correlationId,
            fromJson.messages.getValue("signupRequest").correlationId
        )
    }

    @Test
    fun testVersion2IsRejected() {

        //2.x nests its operations inside channels and has no reply at all: a different model
        val e = assertThrows(SutProblemException::class.java) {
            AsyncApiAccess.parseFromText(
                """
                asyncapi: 2.6.0
                info:
                  title: The previous major version
                  version: 1.0.0
                channels:
                  user/signup:
                    publish:
                      message:
                        payload:
                          type: object
                """.trimIndent()
            )
        }

        assertTrue(e.message!!.contains("2.6.0"), e.message)
        assertTrue(e.message!!.contains("3.x"), e.message)
    }

    @Test
    fun testUnquotedNumericVersionIsStillReadAsText() {

        //YAML would make '3.0' a number, and free-text fields elsewhere likewise
        val schema = AsyncApiAccess.parseFromText(
            """
            asyncapi: 3.0
            info:
              title: Numeric looking
              version: 1.0.0
            """.trimIndent()
        )

        assertEquals("3.0", schema.version)
    }

    @Test
    fun testOpenApiDocumentIsRejected() {

        val e = assertThrows(SutProblemException::class.java) {
            AsyncApiAccess.parseFromText(
                """
                openapi: 3.0.0
                info:
                  title: An OpenAPI document, handed over by mistake
                  version: 1.0.0
                paths: {}
                """.trimIndent()
            )
        }

        //the message has to say what to do about it, not just that it failed
        assertTrue(e.message!!.contains("REST problem type"), e.message)
    }

    @Test
    fun testUnreadableDocumentIsRejected() {

        val e = assertThrows(SutProblemException::class.java) {
            AsyncApiAccess.parseFromText("asyncapi: 3.0.0\n  badly: [indented")
        }

        assertTrue(e.message!!.contains("Failed to parse"), e.message)
    }

    @Test
    fun testDocumentThatIsNotAnObjectIsRejected() {

        val e = assertThrows(SutProblemException::class.java) {
            AsyncApiAccess.parseFromText("- just\n- a list")
        }

        assertTrue(e.message!!.contains("not a JSON/YAML object"), e.message)
    }

    @Test
    fun testDocumentDeclaringNoMessages() {

        //valid, just empty. Nothing to report and nothing to raise
        val schema = AsyncApiAccess.parseFromText(
            """
            asyncapi: 3.0.0
            info:
              title: Empty
              version: 1.0.0
            """.trimIndent()
        )

        assertTrue(schema.messages.isEmpty())
        assertTrue(schema.componentSchemas.isEmpty())
        assertTrue(schema.warnings.isEmpty())
    }

    // ------------------------------------------------------------------ correlation

    @Test
    fun testCorrelationInHeader() {

        val message = load("/asyncapi/artificial/messages.yaml").messages.getValue("signupRequest")

        val correlation = message.correlationId
        assertNotNull(correlation)
        assertEquals(AsyncApiCorrelationId.Source.HEADER, correlation!!.source)
        assertEquals("/correlationId", correlation.pointer)
        assertEquals("correlationId", correlation.fieldName)
    }

    @Test
    fun testCorrelationInPayload() {

        val message = load("/asyncapi/artificial/messages.yaml").messages.getValue("signupReply")

        val correlation = message.correlationId
        assertNotNull(correlation)
        //a transport with no headers, such as a socket, can only carry the id in the payload
        assertEquals(AsyncApiCorrelationId.Source.PAYLOAD, correlation!!.source)
        assertEquals("/request_id", correlation.pointer)
    }

    @Test
    fun testCorrelationExpressionsThatCannotBeUsed() {

        //not one of the two runtime expressions the specification defines
        assertNull(AsyncApiCorrelationId.parse("somewhere/else"))
        assertNull(AsyncApiCorrelationId.parse("\$message.header#"))
        assertNull(AsyncApiCorrelationId.parse("\$message.header#noSlash"))

        //a pointer more than one level deep has no single field name
        val nested = AsyncApiCorrelationId.parse("\$message.payload#/meta/id")
        assertEquals("/meta/id", nested!!.pointer)
        assertNull(nested.fieldName)

        //JSON Pointer escaping is undone, so a field whose name contains a slash still reads
        assertEquals("a/b", AsyncApiCorrelationId.parse("\$message.header#/a~1b")!!.fieldName)

        //surrounding space is not a reason to reject it
        assertEquals(
            AsyncApiCorrelationId.Source.HEADER,
            AsyncApiCorrelationId.parse("  \$message.header#/x  ")!!.source
        )
    }

    @Test
    fun testUnsupportedCorrelationExpressionIsReported() {

        val schema = AsyncApiAccess.parseFromText(
            """
            asyncapi: 3.0.0
            info:
              title: Correlated the wrong way
              version: 1.0.0
            components:
              messages:
                m:
                  correlationId:
                    location: 'somewhere/else'
                  payload:
                    type: object
            """.trimIndent()
        )

        //the message is still perfectly usable, it just cannot be paired with a reply
        assertNull(schema.messages.getValue("m").correlationId)
        assertTrue(schema.warnings.any { it.contains("somewhere/else") }, schema.warnings.toString())
    }

    // ------------------------------------------------------------------ traits

    @Test
    fun testMessageTraitsAreMerged() {

        val message = load("/asyncapi/artificial/messages.yaml").messages.getValue("signupRequest")

        //correlation and headers come from the trait, and are indistinguishable from its own
        assertEquals(AsyncApiCorrelationId.Source.HEADER, message.correlationId!!.source)
        assertTrue(message.headers!!.get("properties").has("correlationId"))
    }

    @Test
    fun testTraitsThatCannotBeUsedAreReported() {

        val schema = AsyncApiAccess.parseFromText(
            """
            asyncapi: 3.0.0
            info:
              title: Broken traits
              version: 1.0.0
            components:
              messageTraits:
                first:
                  title: from the first trait
                  summary: overridden by the second
                second:
                  summary: from the second trait
              messages:
                merged:
                  traits:
                    - ${'$'}ref: '#/components/messageTraits/first'
                    - ${'$'}ref: '#/components/messageTraits/second'
                  description: what the message states itself
                broken:
                  traits:
                    - 'not an object at all'
                    - ${'$'}ref: '#/components/messageTraits/absent'
                  payload:
                    type: object
            """.trimIndent()
        )

        val merged = schema.messages.getValue("merged")
        //traits are merged in declaration order, so the later one wins where they overlap
        assertEquals("from the first trait", merged.title)
        assertEquals("from the second trait", merged.summary)
        assertEquals("what the message states itself", merged.description)

        //an unusable trait costs only the trait: the message survives
        assertTrue(schema.messages.containsKey("broken"))
        assertTrue(schema.warnings.any { it.contains("is not an object") }, schema.warnings.toString())
        assertTrue(schema.warnings.any { it.contains("could not be resolved") }, schema.warnings.toString())
    }

    // ------------------------------------------------------------------ schema formats

    @Test
    fun testAvroDeclaredOnTheComponentSchema() {

        val schema = load("/asyncapi/artificial/message-schema-formats.yaml")

        //Avro is declared on the schema, not on the payload, so the drop has to propagate
        assertFalse(schema.componentSchemas.containsKey("customer-value"))
        assertFalse(schema.messages.containsKey("customer"))

        //while a JSON Schema in the same document is unaffected
        assertTrue(schema.componentSchemas.containsKey("order-value"))
        assertTrue(schema.messages.containsKey("order"))

        assertTrue(schema.warnings.any { it.contains("avro", ignoreCase = true) }, schema.warnings.toString())
    }

    @Test
    fun testMultiFormatWrapperIsUnwrapped() {

        val message = load("/asyncapi/artificial/message-schema-formats.yaml").messages.getValue("wrapped")

        //a dialect that is JSON Schema keeps the schema one level down; it must be lifted out
        assertNull(message.payload!!.get("schemaFormat"))
        assertEquals("object", message.payload!!.get("type").asText())
    }

    // ------------------------------------------------------------------ what a payload can reach

    @Test
    fun testPayloadWhoseSchemaReachesAMissingOneIsDropped() {

        val schema = load("/asyncapi/artificial/message-schema-references.yaml")

        /*
            The payload resolves and so does the schema it names -- it is the schema *that one*
            reaches which is missing. Only following the chain finds it, and it has to be
            found: whatever builds genes from this would fail on a reference it cannot resolve.
         */
        assertFalse(schema.messages.containsKey("nested"))
        assertTrue(schema.warnings.any { it.contains("NotDeclared") }, schema.warnings.toString())
    }

    @Test
    fun testPayloadInAnotherSchemaDialectIsDropped() {

        val schema = load("/asyncapi/artificial/message-schema-references.yaml")

        //'#/definitions/...' is draft-04's layout, and nothing in this document answers it
        assertFalse(schema.messages.containsKey("otherDialect"))
        assertTrue(schema.warnings.any { it.contains("#/definitions/Foo") }, schema.warnings.toString())
    }

    @Test
    fun testPayloadPointingIntoAnotherDocumentIsDropped() {

        val schema = load("/asyncapi/artificial/message-schema-references.yaml")

        //documents split across files are not read yet, so such a payload cannot be built from
        assertFalse(schema.messages.containsKey("otherDocument"))
        assertTrue(schema.warnings.any { it.contains("shared.yaml") }, schema.warnings.toString())
    }

    @Test
    fun testPointerDeeperThanASchemaIsAccepted() {

        val schema = load("/asyncapi/artificial/message-schema-references.yaml")

        //what matters is that the schema it points into is present
        assertTrue(schema.messages.containsKey("deepPointer"))
        assertTrue(schema.messages.containsKey("fine"))
    }

    @Test
    fun testBrokenHeadersCostOnlyTheHeaders() {

        val message = load("/asyncapi/artificial/message-schema-references.yaml").messages.getValue("badHeaders")

        //the message is still perfectly usable, so it is kept without its headers
        assertNotNull(message.payload)
        assertNull(message.headers)
    }

    // ------------------------------------------------------------------ cycles

    @Test
    fun testReferenceCyclesDoNotTakeTheDocumentDown() {

        //without a guard each of these recurses until the stack gives out, killing the run
        val schema = load("/asyncapi/artificial/reference-cycles.yaml")

        //the circular messages and correlation ids are dropped, each explained
        assertFalse(schema.messages.containsKey("ping"))
        assertFalse(schema.messages.containsKey("itself"))
        assertTrue(schema.warnings.any { it.contains("cycle") }, schema.warnings.toString())

        //a correlationId that only points at itself leaves the message without one
        assertNull(schema.messages.getValue("request").correlationId)
    }

    @Test
    fun testSchemasMayReferToThemselves() {

        //a self-referring schema is a tree, and perfectly legitimate: it must not be confused
        //with a broken reference, nor send the reachability check round for ever
        val schema = load("/asyncapi/artificial/reference-cycles.yaml")

        assertTrue(schema.messages.containsKey("request"))
        assertTrue(schema.componentSchemas.containsKey("Node"))
    }
}
