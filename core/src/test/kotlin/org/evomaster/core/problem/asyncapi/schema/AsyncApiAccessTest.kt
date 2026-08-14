package org.evomaster.core.problem.asyncapi.schema

import org.evomaster.core.remote.SutProblemException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * How a document is retrieved, as opposed to what is made of it once it has been.
 *
 * The classpath route is what every other test uses, so what is worth covering here is the one
 * a user actually takes -- a path on disk -- and the errors they will meet when it is wrong.
 */
class AsyncApiAccessTest {

    private val minimal = """
        asyncapi: 3.0.0
        info:
          title: Minimal
          version: 1.0.0
        components:
          messages:
            m:
              payload:
                type: object
    """.trimIndent()

    @Test
    fun testLoadingFromAFilePath(@TempDir dir: Path) {

        val file = Files.writeString(dir.resolve("asyncapi.yaml"), minimal)

        val schema = AsyncApiAccess.getAsyncApiFromLocation(file.toString())

        assertEquals(setOf("m"), schema.messages.keys)
        assertEquals(file.toString(), schema.sourceLocation.location)
        assertEquals(minimal, schema.rawText)
    }

    @Test
    fun testLoadingFromAFileUrl(@TempDir dir: Path) {

        val file = Files.writeString(dir.resolve("asyncapi.yaml"), minimal)

        val schema = AsyncApiAccess.getAsyncApiFromLocation(file.toUri().toString())

        assertEquals(setOf("m"), schema.messages.keys)
    }

    @Test
    fun testAFileThatIsNotThere(@TempDir dir: Path) {

        val e = assertThrows(SutProblemException::class.java) {
            AsyncApiAccess.getAsyncApiFromLocation(dir.resolve("absent.yaml").toString())
        }

        assertTrue(e.message!!.contains("does not exist"), e.message)
    }

    @Test
    fun testAResourceThatIsNotThere() {

        val e = assertThrows(SutProblemException::class.java) {
            AsyncApiAccess.getAsyncApiFromResource("/asyncapi/artificial/absent.yaml")
        }

        assertTrue(e.message!!.contains("classpath"), e.message)
    }
}
