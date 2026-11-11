package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.fixtures.quarkus.BasketResource
import community.flock.wirespec.extractor.fixtures.quarkus.CrateResource
import community.flock.wirespec.extractor.fixtures.quarkus.FruitResource
import community.flock.wirespec.extractor.fixtures.quarkus.OrchardApi
import community.flock.wirespec.extractor.fixtures.quarkus.OrchardResource
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QuarkusRestEndpointExtractorTest {

    private val string = WireType.Primitive(WireType.Primitive.Kind.STRING)
    private val fruit = WireType.Ref("FruitDto")

    private fun extract(resource: Class<*>, vararg others: Class<*>): Map<String, Endpoint> =
        JaxRsEndpointExtractor(TypeExtractor()).extract(resource, others.toList() + resource).associateBy { it.name }

    private val fruits = extract(FruitResource::class.java)

    @Test
    fun `Rest shorthand annotations bind like their JAX-RS equivalents`() {
        val list = fruits.getValue("List")
        list.queryParams.map { it.name } shouldContainExactly listOf("filter")
        // An unnamed @RestHeader derives the header from the parameter name, as Quarkus does.
        list.headerParams.map { it.name } shouldContainExactly listOf("X-Trace-Id")
        list.requestBody shouldBe null

        val byName = fruits.getValue("ByName")
        byName.pathSegments shouldBe listOf(PathSegment.Literal("fruits"), PathSegment.Variable("name", string))
        byName.requestBody shouldBe null
    }

    @Test
    fun `a bare parameter named after a path variable is a path parameter`() {
        val version = fruits.getValue("Version")
        version.pathSegments shouldBe listOf(
            PathSegment.Literal("fruits"),
            PathSegment.Variable("name", string),
            PathSegment.Literal("versions"),
            PathSegment.Variable("version", WireType.Primitive(WireType.Primitive.Kind.INTEGER_32)),
        )
        // Neither the implicit path parameters nor the injected UriInfo is the entity.
        version.requestBody shouldBe null
    }

    @Test
    fun `a bare parameter stays the entity when the path variable is bound explicitly`() {
        val update = fruits.getValue("Update")
        update.pathSegments.last() shouldBe PathSegment.Variable("name", string)
        update.requestBody shouldBe fruit
    }

    @Test
    fun `Mutiny, CompletionStage and RestResponse wrappers are unwrapped`() {
        fruits.getValue("ByName").responses shouldBe listOf(Endpoint.Response(200, fruit))
        fruits.getValue("Stream").responses shouldBe listOf(Endpoint.Response(200, WireType.ListOf(fruit)))
        fruits.getValue("Update").responses shouldBe listOf(Endpoint.Response(200, fruit))
        fruits.getValue("Async").responses shouldBe listOf(Endpoint.Response(200, fruit))
    }

    @Test
    fun `ResponseStatus sets the status`() {
        val create = fruits.getValue("Create")
        create.requestBody shouldBe fruit
        create.responses shouldBe listOf(Endpoint.Response(201, fruit))
    }

    @Test
    fun `an untyped Response has no known body`() {
        fruits.getValue("Delete").responses shouldBe listOf(Endpoint.Response(200, null))
    }

    @Test
    fun `a suspend function's continuation is not the request entity`() {
        val suspended = fruits.getValue("Suspended")
        suspended.requestBody shouldBe null
        suspended.responses shouldBe listOf(Endpoint.Response(200, fruit))
    }

    @Test
    fun `annotations are inherited from an interface the resource implements`() {
        val basket = extract(BasketResource::class.java).getValue("Basket")
        basket.method shouldBe Endpoint.HttpMethod.GET
        basket.pathSegments shouldBe listOf(PathSegment.Literal("baskets"), PathSegment.Variable("id", string))
        basket.queryParams.map { it.name } shouldContainExactly listOf("expand")
        basket.requestBody shouldBe null
        basket.responses shouldBe listOf(Endpoint.Response(200, fruit))
    }

    @Test
    fun `an implementation of a generic contract contributes the concrete types`() {
        val create = extract(CrateResource::class.java).getValue("Create")
        create.requestBody shouldBe fruit
        create.responses shouldBe listOf(Endpoint.Response(200, fruit))
    }

    @Test
    fun `endpoints inherited from another root resource are emitted once`() {
        extract(OrchardApi::class.java, OrchardResource::class.java).keys shouldContainExactly setOf("Orchards")
        extract(OrchardResource::class.java, OrchardApi::class.java).values.shouldBeEmpty()
    }
}
