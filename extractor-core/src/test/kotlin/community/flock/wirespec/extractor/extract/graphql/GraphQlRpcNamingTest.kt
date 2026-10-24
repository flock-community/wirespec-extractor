package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.Rpc.Kind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GraphQlRpcNamingTest {

    private fun rpc(field: String, kind: Kind = Kind.QUERY) = Rpc("Controller", field, kind, emptyList(), null)

    private fun names(vararg rpcs: Rpc, types: Set<String> = emptySet()): List<String> =
        GraphQlRpcNaming.name(rpcs.toList(), types).map { it.name }

    @Test
    fun `an rpc is named after its field`() {
        names(rpc("addTodo"), rpc("todos")) shouldBe listOf("AddTodo", "Todos")
    }

    @Test
    fun `a name taken by a type takes the root type as prefix`() {
        names(rpc("todo"), rpc("todo", Kind.MUTATION), types = setOf("Todo")) shouldBe listOf("QueryTodo", "MutationTodo")
    }

    @Test
    fun `a name taken by an earlier rpc takes the root type as prefix`() {
        names(rpc("reset"), rpc("reset", Kind.MUTATION)) shouldBe listOf("Reset", "MutationReset")
    }

    @Test
    fun `a name that is still taken gets a numeric suffix`() {
        names(rpc("todo"), types = setOf("Todo", "QueryTodo")) shouldBe listOf("QueryTodo2")
    }

    @Test
    fun `a reserved or invalid name takes the root type as prefix`() {
        names(rpc("string"), rpc("_service")) shouldBe listOf("QueryString", "Service")
        names(rpc("__typename")) shouldBe listOf("Typename")
    }
}
