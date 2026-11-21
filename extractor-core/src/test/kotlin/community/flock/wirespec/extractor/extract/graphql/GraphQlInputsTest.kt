package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.GraphQlField
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GraphQlInputsTest {

    private fun ref(name: String, nullable: Boolean = false) = WireType.Ref(name, nullable)

    private fun rpc(arguments: List<WireType>, result: WireType?, kind: Rpc.Kind = Rpc.Kind.MUTATION) =
        Rpc("Controller", "op", kind, arguments.mapIndexed { i, t -> WireType.Field("a$i", t) }, result)

    private val objects = mapOf(
        "NewTodo" to listOf(ref("Details"), WireType.Primitive(WireType.Primitive.Kind.STRING)),
        "Details" to listOf(WireType.Primitive(WireType.Primitive.Kind.STRING)),
        "Todo" to listOf(ref("Owner")),
        "Owner" to listOf(WireType.Primitive(WireType.Primitive.Kind.STRING)),
        "Filter" to emptyList(),
    )

    @Test
    fun `a type an argument reaches, directly or through its fields, is an input`() {
        val inputs = GraphQlInputs.of(objects, listOf(rpc(listOf(ref("NewTodo")), ref("Todo"))), emptyList())

        inputs.inputOnly shouldBe setOf("NewTodo", "Details")
    }

    @Test
    fun `a type that is also returned is not marked, so the converter writes an Input copy`() {
        val rpcs = listOf(
            rpc(listOf(WireType.ListOf(ref("Owner"))), ref("Todo")),
        )
        val inputs = GraphQlInputs.of(objects, rpcs, emptyList())

        // Owner is a field of the returned Todo, so it is an output as well.
        inputs.inputOnly shouldBe emptySet()
    }

    @Test
    fun `arguments of a field of a type are inputs and its result an output`() {
        val related = Rpc(
            ownerSimpleName = "Controller",
            field = "related",
            kind = Rpc.Kind.QUERY,
            arguments = listOf(WireType.Field("filter", ref("Filter", nullable = true))),
            result = WireType.ListOf(ref("Todo")),
            parentTypeName = "Todo",
            ofDataType = true,
        )
        val inputs = GraphQlInputs.of(objects, listOf(related), emptyList())

        inputs.inputOnly shouldBe setOf("Filter")
    }

    @Test
    fun `results of field resolvers without arguments are outputs`() {
        val owner = mapOf("Owner" to listOf(WireType.Primitive(WireType.Primitive.Kind.STRING)))
        val rpcs = listOf(rpc(listOf(ref("Owner")), null))
        val fields = listOf(GraphQlField("Todo", "owner", ref("Owner")))

        GraphQlInputs.of(owner, rpcs, fields).inputOnly shouldBe emptySet()
        GraphQlInputs.of(owner, rpcs, emptyList()).inputOnly shouldBe setOf("Owner")
    }

    @Test
    fun `without arguments that take objects there are no inputs`() {
        GraphQlInputs.of(objects, listOf(rpc(listOf(WireType.Primitive(WireType.Primitive.Kind.STRING)), ref("Todo"))), emptyList()) shouldBe
            GraphQlInputs.NONE
    }
}
