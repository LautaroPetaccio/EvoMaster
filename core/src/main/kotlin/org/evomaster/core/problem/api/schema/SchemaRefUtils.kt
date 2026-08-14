package org.evomaster.core.problem.api.schema

import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Working out what a `$ref` points at, and where the document holding it lives.
 *
 * None of this is specific to a schema language: OpenAPI and AsyncAPI both write references
 * the same way, as a location followed by a `#` and a JSON Pointer, and both need to know
 * whether a reference stays inside the current document or leads to another one.
 */
object SchemaRefUtils {

    private val log = LoggerFactory.getLogger(SchemaRefUtils::class.java)

    /**
     * Whether the reference stays inside the document making it.
     */
    fun isLocalRef(sref: String) = sref.startsWith("#")

    /**
     * The absolute location of the document a reference points at, resolved against the
     * document making the reference. Null when the reference is not one this can make sense of,
     * in which case [messages] says why.
     */
    fun computeLocation(ref: String, currentSource: SchemaLocation, messages: MutableList<String>): String? {

        val rawLocation = extractLocation(ref, messages)
            ?: return null

        if (rawLocation.startsWith("http:", true) || rawLocation.startsWith("https:", true)) {
            //location is absolute, so no need to do anything
            return rawLocation
        }

        //TODO does it make any sense to have file:// here???

        if (currentSource.type == SchemaLocationType.MEMORY) {
            throw IllegalArgumentException("Can't handle relative location for memory files: $rawLocation")
        }

        val csl = currentSource.location

        if (rawLocation.startsWith("//")) {
            //as per specs, use same protocol as source
            val separator = csl.indexOf(":")
            if (separator < 0) {
                /*
                    A protocol-relative reference read from something that has no protocol, such
                    as a plain file path. There is nothing to borrow, so the reference cannot be
                    resolved.
                 */
                messages.add("No protocol can be inferred for $rawLocation from $csl")
                return null
            }
            return "${csl.substring(0, separator)}:$rawLocation"
        }

        //if arrive here, it is a relative path
        val delimiter = if (csl.endsWith("/")) "" else "/"
        val parentFolder = "../" // this is based to what discussed in the specs

        val location = "$csl$delimiter$parentFolder$rawLocation"

        //FIXME should not usi URI
        return try {
            URI(location).normalize().toString()
        } catch (e: Exception) {
            location
        }
    }

    private fun extractLocation(sref: String, messages: MutableList<String>): String? {
        if (!sref.contains("#")) {
            messages.add("Not a valid \$ref, as it contains no #: $sref")
            return null
        }
        return sref.substring(0, sref.indexOf("#"))
    }
}
