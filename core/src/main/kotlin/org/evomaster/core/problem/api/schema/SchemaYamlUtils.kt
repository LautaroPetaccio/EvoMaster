package org.evomaster.core.problem.api.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder
import org.yaml.snakeyaml.LoaderOptions

/**
 * Reading an API schema document as a tree, whatever schema language it is written in.
 *
 * One reader handles both YAML and JSON, as JSON is valid YAML. snakeyaml's out-of-the-box
 * limits are far too low for real documents, so they are raised here, in one place rather than
 * at each call site.
 */
object SchemaYamlUtils {

    fun readTree(text: String): JsonNode = mapper().readTree(text)

    private fun mapper(): ObjectMapper {

        val yaml = YAMLFactoryBuilder(YAMLFactory())
            .loaderOptions(LoaderOptions().apply {
                codePointLimit = 50 * 1024 * 1024 // 50MB
                maxAliasesForCollections = 1000
                nestingDepthLimit = 100
            })
            .build()

        return ObjectMapper(yaml)
    }
}
