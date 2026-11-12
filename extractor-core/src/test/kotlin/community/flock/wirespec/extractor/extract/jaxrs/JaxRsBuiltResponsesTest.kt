package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.fixtures.jaxrs.built.BuiltResponsesResource
import community.flock.wirespec.extractor.fixtures.jaxrs.built.MicroProfileResource
import community.flock.wirespec.extractor.fixtures.jaxrs.built.ReadinessEndpoint
import community.flock.wirespec.extractor.fixtures.quarkus.OrderResource
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.Response
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Responses read from the `Response` / `RestResponse` a JAX-RS resource method builds in
 * its body (issue #57).
 */
class JaxRsBuiltResponsesTest {

    private val string = WireType.Primitive(WireType.Primitive.Kind.STRING)
    private val fruit = WireType.Ref("Fruit")
    private val problem = WireType.Ref("Problem")

    private val warnings = mutableListOf<String>()

    private fun extract(resource: Class<*>): Map<String, Endpoint> =
        JaxRsEndpointExtractor(TypeExtractor(), onWarn = { warnings += it }).extract(resource).associateBy { it.name }

    private val built by lazy { extract(BuiltResponsesResource::class.java) }

    private fun responses(name: String) = built.getValue(name).responses

    @Test
    fun `the readiness endpoint from the issue returns 200 and 503 with a String`() {
        extract(ReadinessEndpoint::class.java).getValue("Ready").responses shouldBe listOf(
            Response(200, string),
            Response(503, string),
        )
        warnings.shouldBeEmpty()
    }

    @Test
    fun `a factory fixes the status`() {
        responses("Created") shouldBe listOf(Response(201, null))
        responses("Accepted") shouldBe listOf(Response(202, null))
        responses("NotModified") shouldBe listOf(Response(304, null))
        responses("ServerError") shouldBe listOf(Response(500, null))
    }

    @Test
    fun `a status is read from an int, a status constant and its code`() {
        responses("Teapot") shouldBe listOf(Response(418, null))
        responses("Conflict") shouldBe listOf(Response(409, null))
        responses("Gone") shouldBe listOf(Response(410, null))
    }

    @Test
    fun `every status the method returns becomes a response, with the entity's type`() {
        responses("ByName") shouldBe listOf(Response(200, fruit), Response(404, problem))
        responses("Delete") shouldBe listOf(Response(204, null), Response(404, null))
    }

    @Test
    fun `an entity keeps the generic type of the call that produced it`() {
        responses("List") shouldBe listOf(Response(200, WireType.ListOf(fruit)))
        responses("Generic") shouldBe listOf(Response(200, WireType.ListOf(fruit)))
    }

    @Test
    fun `a builder kept in a local is followed through every branch`() {
        responses("Create") shouldBe listOf(Response(201, fruit), Response(409, null))
    }

    @Test
    fun `entities of different types for one status are Any`() {
        responses("Mixed") shouldBe listOf(Response(200, WireType.Any()))
    }

    @Test
    fun `a status the body does not reveal falls back to a body-less 200 with a warning`() {
        responses("Dynamic") shouldBe listOf(Response(200, null))
        responses("Delegated") shouldBe listOf(Response(200, null))
        responses("Async") shouldBe listOf(Response(200, null))

        warnings.filter { "BuiltResponsesResource#" in it }.map { it.substringAfter('#').substringBefore(' ') }
            .shouldContainExactlyInAnyOrder("dynamic", "delegated", "async")
        warnings.first() shouldContain "@ApiResponse"
    }

    @Test
    fun `ApiResponse overrides the inferred responses and the inferred body backs a declared status`() {
        responses("Declared") shouldBe listOf(Response(200, fruit), Response(404, problem))
        // Declared, so not warned about even though the body cannot be read.
        responses("DeclaredDelegated") shouldBe listOf(Response(404, problem))
        warnings.none { "#declaredDelegated" in it } shouldBe true
    }

    @Test
    fun `Quarkus ResponseStatus overrides the inferred status`() {
        responses("Explicit") shouldBe listOf(Response(201, null))
    }

    @Test
    fun `a typed RestResponse reports every status, with its type argument as the entity`() {
        responses("Rest") shouldBe listOf(Response(200, fruit), Response(404, null))
        responses("RestBuilder") shouldBe listOf(Response(202, fruit), Response(400, null))
    }

    @Test
    fun `a raw RestResponse carries the entity it was given`() {
        responses("Raw") shouldBe listOf(Response(201, string))
    }

    @Test
    fun `MicroProfile APIResponse is read like swagger's ApiResponse`() {
        val resource = extract(MicroProfileResource::class.java)
        resource.getValue("List").responses shouldBe listOf(Response(200, WireType.ListOf(fruit)), Response(503, null))
        resource.getValue("ByName").responses shouldBe listOf(Response(200, fruit), Response(404, problem))
        warnings.shouldBeEmpty()
    }

    @Test
    fun `Kotlin let-elvis, when and suspend functions are followed`() {
        val orders = extract(OrderResource::class.java)
        val fruitDto = WireType.Ref("FruitDto")
        orders.getValue("ById").responses shouldBe listOf(Response(200, fruitDto), Response(404, null))
        orders.getValue("Delete").responses shouldBe listOf(Response(204, null), Response(404, null))
        orders.getValue("Suspended").responses shouldBe listOf(Response(200, fruitDto), Response(404, null))
        orders.getValue("OpenSuspended").responses shouldBe listOf(Response(200, fruitDto), Response(404, null))
        warnings.shouldBeEmpty()
    }

    /**
     * Guards the fixture, not the scanner: if Kotlin ever stopped splitting an `open`
     * suspend function into a `$suspendImpl`, the test above would stop covering that shape.
     */
    @Test
    fun `an open suspend function really compiles to a separate suspendImpl body`() {
        OrderResource::class.java.declaredMethods.any { it.name == "openSuspended\$suspendImpl" } shouldBe true
    }

    @Test
    fun `the scanner reports nothing for a body it cannot follow`() {
        val method = BuiltResponsesResource::class.java.getMethod("delegated")
        JaxRsResponseScanner.scan(method).shouldBeEmpty()
    }
}
