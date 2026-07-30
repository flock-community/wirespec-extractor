// src/test/kotlin/community/flock/wirespec/extractor/fixtures/InterfaceParamsController.kt
package community.flock.wirespec.extractor.fixtures

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * API declared on an interface, generated-openapi style: mapping and parameter annotations
 * live here, the implementation carries none. Java does not inherit parameter annotations,
 * so these are only visible by resolving the overridden interface method.
 */
interface ItemsApi {

    @GetMapping("/api/items/search")
    fun search(
        @RequestParam externalId: String,
        @RequestParam(required = false) includeInactive: Boolean?,
    ): String

    @PostMapping("/api/items/search")
    fun searchPost(@RequestBody request: String): String
}

@RestController
class InterfaceParamsController : ItemsApi {
    override fun search(externalId: String, includeInactive: Boolean?): String = ""
    override fun searchPost(request: String): String = ""
}

/** Override that re-annotates: the implementation's declaration must win over the interface's. */
interface NamedApi {
    @GetMapping("/api/named")
    fun search(@RequestParam("ifaceName") value: String): String
}

@RestController
class OverridingParamsController : NamedApi {
    override fun search(@RequestParam("implName") value: String): String = ""
}

/** Generic variant: the override's parameter type only matches the interface's after resolving `T`. */
interface CrudApi<T> {
    @PostMapping("/api/crud")
    fun create(@RequestBody entity: T): String
}

@RestController
class StringCrudController : CrudApi<String> {
    override fun create(entity: String): String = ""
}
