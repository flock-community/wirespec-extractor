package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.GraphQlNesting
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.Rpc.Kind
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GraphQlNamespacesTest {

    private fun rpc(
        field: String,
        parent: String = "Query",
        result: WireType? = WireType.Primitive(WireType.Primitive.Kind.BOOLEAN),
        kind: Kind = Kind.QUERY,
        owner: String = "Controller",
        arguments: List<WireType.Field> = emptyList(),
    ) = Rpc(owner, field, kind, arguments, result, parentTypeName = parent)

    private val namespace = WireType.Ref("Namespace")
    private val inbox = rpc("inbox", parent = "NotificationsQuery", owner = "InboxController")

    private fun of(vararg rpcs: Rpc) = GraphQlNamespaces.of(rpcs.toList(), emptyTypes = { setOf("Namespace", "NotificationsQuery") })

    @Test
    fun `a field that returns an empty object opens the namespace named after it and is no rpc`() {
        val notifications = rpc("notifications", result = namespace)
        val namespaces = of(notifications, inbox)

        namespaces.operations shouldBe listOf(inbox)
        namespaces.path(listOf(inbox)) shouldBe listOf(GraphQlNesting(Kind.QUERY, "Query", "notifications", "NotificationsQuery", nullable = false))
    }

    @Test
    fun `a field that returns nothing or an object named after the namespace opens it too`() {
        of(rpc("notifications", result = null), inbox).operations shouldBe listOf(inbox)
        of(rpc("inbox", result = WireType.Ref("NotificationsQuery", nullable = true)), inbox).let { namespaces ->
            namespaces.operations shouldBe listOf(inbox)
            namespaces.path(listOf(inbox)).single().let {
                it.field shouldBe "inbox"
                it.nullable shouldBe true
            }
        }
    }

    @Test
    fun `a field with a result, with arguments, or without a namespace is an rpc`() {
        val withResult = rpc("notifications", result = WireType.Ref("Notifications"))
        val withArguments = rpc("notifications", result = namespace, arguments = listOf(WireType.Field("id", WireType.Primitive(WireType.Primitive.Kind.STRING))))
        val withoutNamespace = rpc("settings", result = namespace)

        of(withResult, withArguments, withoutNamespace, inbox).let { namespaces ->
            namespaces.operations shouldBe listOf(withResult, withArguments, withoutNamespace, inbox)
            namespaces.path(listOf(inbox)).shouldBeEmpty()
        }
    }

    @Test
    fun `a field of a type that holds data opens no namespace`() {
        val books = Rpc("Controller", "notifications", Kind.QUERY, emptyList(), namespace, parentTypeName = "User", ofDataType = true)
        of(books, inbox).operations shouldBe listOf(books, inbox)
    }

    @Test
    fun `a field only opens a namespace of its own operation type`() {
        val mutation = rpc("notifications", result = namespace, kind = Kind.MUTATION, parent = "Mutation")
        of(mutation, inbox).operations shouldBe listOf(mutation, inbox)
    }

    @Test
    fun `the path to a nested namespace starts at the root type`() {
        val search = rpc("search", parent = "ArchiveQuery")
        val namespaces = of(
            rpc("archive", parent = "NotificationsQuery", result = namespace),
            rpc("notifications", result = namespace),
            inbox,
            search,
        )

        namespaces.operations shouldBe listOf(inbox, search)
        namespaces.path(listOf(search, inbox)) shouldBe listOf(
            GraphQlNesting(Kind.QUERY, "Query", "notifications", "NotificationsQuery", nullable = false),
            GraphQlNesting(Kind.QUERY, "NotificationsQuery", "archive", "ArchiveQuery", nullable = false),
        )
    }

    @Test
    fun `the rpcs of a root type need no path`() {
        val ping = rpc("ping")
        of(rpc("notifications", result = namespace), inbox, ping).path(listOf(ping)).shouldBeEmpty()
    }
}
