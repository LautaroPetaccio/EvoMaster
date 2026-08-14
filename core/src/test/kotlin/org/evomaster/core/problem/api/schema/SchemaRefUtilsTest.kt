package org.evomaster.core.problem.api.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaRefUtilsTest {

    private val messages = mutableListOf<String>()

    @Test
    fun testLocalReference() {
        assertTrue(SchemaRefUtils.isLocalRef("#/components/schemas/Foo"))
        assertTrue(SchemaRefUtils.isLocalRef("#"))
        assertFalse(SchemaRefUtils.isLocalRef("other.yaml#/components/schemas/Foo"))
        assertFalse(SchemaRefUtils.isLocalRef("https://example.org/x.yaml#/a"))
    }

    @Test
    fun testAbsoluteLocation() {

        val source = SchemaLocation.ofLocal("/tmp/api.yaml")

        assertEquals(
            "https://example.org/other.yaml",
            SchemaRefUtils.computeLocation("https://example.org/other.yaml#/a", source, messages)
        )
    }

    @Test
    fun testRelativeLocation() {

        val source = SchemaLocation.ofLocal("/tmp/schemas/api.yaml")

        assertEquals(
            "/tmp/schemas/other.yaml",
            SchemaRefUtils.computeLocation("other.yaml#/a", source, messages)
        )
        assertEquals(
            "/tmp/schemas/sub/other.yaml",
            SchemaRefUtils.computeLocation("sub/other.yaml#/a", source, messages)
        )
        assertEquals(
            "/tmp/other.yaml",
            SchemaRefUtils.computeLocation("../other.yaml#/a", source, messages)
        )
    }

    @Test
    fun testProtocolRelativeLocationBorrowsTheProtocol() {

        val source = SchemaLocation.ofRemote("https://example.org/api.yaml")

        assertEquals(
            "https://elsewhere.org/other.yaml",
            SchemaRefUtils.computeLocation("//elsewhere.org/other.yaml#/a", source, messages)
        )
    }

    @Test
    fun testProtocolRelativeLocationWithNoProtocolToBorrow() {

        //a plain file path has no protocol, so there is nothing to resolve the reference against
        val source = SchemaLocation.ofLocal("/tmp/schemas/api.yaml")

        assertNull(SchemaRefUtils.computeLocation("//elsewhere.org/other.yaml#/a", source, messages))
        assertTrue(messages.any { it.contains("No protocol") }, messages.toString())
    }

    @Test
    fun testReferenceWithNoFragment() {

        assertNull(SchemaRefUtils.computeLocation("other.yaml", SchemaLocation.ofLocal("/tmp/a.yaml"), messages))
        assertTrue(messages.any { it.contains("contains no #") }, messages.toString())
    }

    @Test
    fun testRelativeLocationFromADocumentWithNoLocation() {

        assertThrows(IllegalArgumentException::class.java) {
            SchemaRefUtils.computeLocation("other.yaml#/a", SchemaLocation.MEMORY, messages)
        }
    }
}
