package org.evomaster.core.problem.asyncapi.schema

import org.evomaster.core.problem.rest.schema.SchemaLocation
import org.evomaster.core.problem.rest.schema.SchemaLocationType
import org.evomaster.core.remote.HttpClientFactory
import org.evomaster.core.remote.SutProblemException
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import javax.ws.rs.core.Response

/**
 * Retrieves AsyncAPI documents and hands them to [AsyncApiParser].
 *
 * This is the AsyncAPI counterpart of
 * [org.evomaster.core.problem.rest.schema.OpenApiAccess], and it is deliberately the same
 * shape: a document may come from a URL, from disk, or from the classpath, and where it came
 * from is remembered so that references to neighbouring documents can be followed.
 */
object AsyncApiAccess {

    private val log = LoggerFactory.getLogger(AsyncApiAccess::class.java)

    private const val CONNECTION_ATTEMPTS = 10

    /**
     * Retrieve and parse an AsyncAPI document. The location can be a remote http(s) URL, a
     * local file URL, or a plain file path.
     */
    fun getAsyncApiFromLocation(location: String): AsyncApiSchema {

        val type = if (location.startsWith("http", true)) {
            SchemaLocationType.REMOTE
        } else {
            SchemaLocationType.LOCAL
        }

        return parse(fetch(location, type), SchemaLocation(location, type))
    }

    /**
     * Retrieve and parse an AsyncAPI document off the classpath. Only meant for tests.
     */
    fun getAsyncApiFromResource(location: String): AsyncApiSchema =
        parse(fetch(location, SchemaLocationType.RESOURCE), SchemaLocation(location, SchemaLocationType.RESOURCE))

    /**
     * Parse a document that is already in hand.
     */
    fun parseFromText(schemaText: String): AsyncApiSchema = parse(schemaText, SchemaLocation.MEMORY)

    /*
        Note that nothing is reported to the user here. Everything the parser had to skip is on
        AsyncApiSchema.warnings, and where those warnings go is the sampler's decision: the REST
        sampler both logs them and files them with the warnings aggregator, so that they reach
        the machine-readable report. Logging them here would pre-empt that and duplicate it.
     */
    private fun parse(schemaText: String, location: SchemaLocation): AsyncApiSchema =
        AsyncApiParser.parse(schemaText, location)

    /**
     * Read the text of a document, wherever it lives.
     */
    private fun fetch(location: String, type: SchemaLocationType): String = when (type) {
        SchemaLocationType.REMOTE -> readFromRemoteServer(location)
        SchemaLocationType.RESOURCE -> readFromResource(location)
        SchemaLocationType.LOCAL -> readFromDisk(location)
        SchemaLocationType.MEMORY ->
            throw SutProblemException("There is no document to retrieve at '$location'")
    }

    private fun readFromResource(location: String): String {

        val resource = this.javaClass.getResource(location)
            ?: throw SutProblemException("Cannot find the AsyncAPI document on the classpath: $location")

        return resource.readText()
    }

    private fun readFromDisk(location: String): String {

        val fileScheme = "file:"

        val path = try {
            if (location.startsWith(fileScheme, true)) {
                Paths.get(URI.create(location))
            } else {
                Paths.get(location)
            }
        } catch (e: Exception) {
            throw SutProblemException(
                "The file path provided for the AsyncAPI schema $location" +
                        " ended up with the following error: " + e.message
            )
        }

        if (!Files.exists(path)) {
            throw SutProblemException("The provided AsyncAPI file does not exist: $location")
        }

        return path.toFile().readText()
    }

    private fun readFromRemoteServer(url: String): String {

        val response = connectToServer(url)

        val body = response.readEntity(String::class.java)

        if (response.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
            throw SutProblemException(
                "Cannot retrieve the AsyncAPI schema from $url , status=${response.status} , body: $body"
            )
        }

        return body
    }

    private fun connectToServer(url: String): Response {

        for (i in 0 until CONNECTION_ATTEMPTS) {
            try {
                return HttpClientFactory.createTrustingJerseyClient()
                    .target(url)
                    .request("*/*") //cannot assume it is in JSON... could be YAML as well
                    .get()
            } catch (e: Exception) {
                if (e.cause is ConnectException) {
                    /*
                        Even if the SUT is running, whatever serves the schema might not be
                        ready yet. So let's just wait a bit, and then retry
                     */
                    log.debug("Failed to connect to $url, retrying")
                    Thread.sleep(1_000)
                } else {
                    throw SutProblemException("Failed to connect to $url: ${e.message}")
                }
            }
        }

        throw SutProblemException("Check if the schema's URL is correct. Failed to connect to $url")
    }
}
