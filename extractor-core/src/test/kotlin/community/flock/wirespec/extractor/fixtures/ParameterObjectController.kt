// src/test/kotlin/community/flock/wirespec/extractor/fixtures/ParameterObjectController.kt
package community.flock.wirespec.extractor.fixtures

import org.springdoc.core.annotations.ParameterObject
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A springdoc `@ParameterObject`: Spring binds each property from its own query
 * parameter, so the extractor must flatten the POJO into individual QUERY params.
 */
data class ProductSearchRequest(
    val externalId: String?,
    val includeInactive: Boolean?,
    val page: Int = 0,
    val tags: List<String>? = null,
    /** Not flattenable: would need a nested `filter.name` query name. */
    val filter: NestedFilter? = null,
)

data class NestedFilter(val name: String?)

@RestController
class ParameterObjectController {

    @GetMapping("/api/products/search")
    fun search(@ParameterObject request: ProductSearchRequest): String = ""
}

/** The annotation on the interface only — invisible without overridden-method resolution. */
interface ParameterObjectApi {
    @GetMapping("/api/iface/products/search")
    fun search(@ParameterObject request: ProductSearchRequest): String
}

@RestController
class InterfaceParameterObjectController : ParameterObjectApi {
    override fun search(request: ProductSearchRequest): String = ""
}
