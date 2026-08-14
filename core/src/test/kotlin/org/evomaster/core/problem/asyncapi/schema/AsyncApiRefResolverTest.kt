package org.evomaster.core.problem.asyncapi.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for reference handling, at a finer grain than a whole document allows.
 */
class AsyncApiRefResolverTest {

    private fun document() = AsyncApiParser.readTree(
        """
        asyncapi: 3.0.0
        components:
          messages:
            placed:
              name: OrderPlaced
              payload:
                type: object
                properties:
                  item:
                    ${'$'}ref: '#/components/schemas/with~1slash'
                  history:
                    type: array
                    items:
                      ${'$'}ref: '#/components/schemas/with~0tilde'
          schemas:
            with/slash:
              type: string
            with~tilde:
              type: integer
        """.trimIndent()
    )

    @Test
    fun testLocalReferenceIsFollowed() {

        val resolved = AsyncApiRefResolver.resolveLocal(document(), "#/components/messages/placed")

        assertNotNull(resolved)
        assertEquals("OrderPlaced", resolved!!.get("name").asText())
    }

    @Test
    fun testReferenceToTheDocumentRootIsTheDocument() {
        assertEquals(document(), AsyncApiRefResolver.resolveLocal(document(), "#"))
    }

    @Test
    fun testReferenceThatLeadsNowhere() {
        assertNull(AsyncApiRefResolver.resolveLocal(document(), "#/components/messages/absent"))
        assertNull(AsyncApiRefResolver.resolveLocal(document(), "#/nothing/here/at/all"))
    }

    @Test
    fun testReferenceToAnotherDocumentIsNotLocal() {

        assertFalse(AsyncApiRefResolver.isLocal("other.yaml#/components/schemas/Order"))
        assertNull(AsyncApiRefResolver.resolveLocal(document(), "other.yaml#/components/messages/placed"))
    }

    @Test
    fun testEscapedPointerSegments() {

        //JSON Pointer escapes '/' as '~1' and '~' as '~0'
        assertEquals(
            "string",
            AsyncApiRefResolver.resolveLocal(document(), "#/components/schemas/with~1slash")!!.get("type").asText()
        )
        assertEquals(
            "integer",
            AsyncApiRefResolver.resolveLocal(document(), "#/components/schemas/with~0tilde")!!.get("type").asText()
        )
    }

    @Test
    fun testKeyOfAReferenceWithTheExpectedShape() {

        assertEquals(
            "placed",
            AsyncApiRefResolver.refKey("#/components/messages/placed", "#/components/messages/")
        )
    }

    @Test
    fun testKeyOfAReferenceWithAnotherShape() {

        //a pointer into a channel is not a component message, however similar it looks
        assertNull(
            AsyncApiRefResolver.refKey("#/channels/orders/messages/placed", "#/components/messages/")
        )
        //nor is a deeper pointer at the expected place
        assertNull(
            AsyncApiRefResolver.refKey("#/components/messages/placed/payload", "#/components/messages/")
        )
        assertNull(AsyncApiRefResolver.refKey("#/components/messages/", "#/components/messages/"))
    }

    @Test
    fun testSchemaKeyOfAReference() {

        //unlike refKey, a pointer that goes deeper still names the schema it goes into
        assertEquals("Order", AsyncApiRefResolver.schemaKeyOf("#/components/schemas/Order"))
        assertEquals(
            "Order",
            AsyncApiRefResolver.schemaKeyOf("#/components/schemas/Order/properties/item")
        )
        assertEquals("with/slash", AsyncApiRefResolver.schemaKeyOf("#/components/schemas/with~1slash"))

        assertNull(AsyncApiRefResolver.schemaKeyOf("#/definitions/Order"))
        assertNull(AsyncApiRefResolver.schemaKeyOf("#/components/messages/order"))
        assertNull(AsyncApiRefResolver.schemaKeyOf("other.yaml#/components/schemas/Order"))
        assertNull(AsyncApiRefResolver.schemaKeyOf("#/components/schemas/"))
    }

    @Test
    fun testReferencesAreFoundAtAnyDepth() {

        val refs = AsyncApiRefResolver.collectRefs(document())

        //one nested three levels inside a schema, one further down inside an array
        assertEquals(
            listOf("#/components/schemas/with~1slash", "#/components/schemas/with~0tilde"),
            refs
        )
    }

    @Test
    fun testRefOfSomethingThatIsNotAReference() {

        assertNull(AsyncApiRefResolver.refOf(null))
        assertNull(AsyncApiRefResolver.refOf(document().get("components")))
        assertEquals(
            "#/components/schemas/with~1slash",
            AsyncApiRefResolver.refOf(
                document().get("components").get("messages").get("placed")
                    .get("payload").get("properties").get("item")
            )
        )
    }
}
