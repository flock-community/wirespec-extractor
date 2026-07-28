package community.flock.wirespec.extractor.ktor

import community.flock.wirespec.extractor.ExtractConfig
import community.flock.wirespec.extractor.WirespecExtractor
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class KtorExtractorTest {

    private fun thisModuleClassesDirs(): List<File> {
        val probe = community.flock.wirespec.extractor.fixtures.ktor.KtorUserClient::class.java
        val kotlinDir = File(probe.protectionDomain.codeSource.location.toURI())
        val javaDir = kotlinDir.parentFile?.parentFile?.resolve("java/test")
        return listOfNotNull(kotlinDir, javaDir?.takeIf { it.exists() })
    }

    private fun extract(tmp: Path): List<File> =
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = thisModuleClassesDirs(),
                runtimeClasspath = emptyList(),
                outputDirectory = File(tmp.toFile(), "ws").apply { mkdirs() },
                basePackage = "community.flock.wirespec.extractor.fixtures.ktor",
            )
        ).filesWritten

    @Test
    fun `extracts Ktor server routing into a ws file`(@TempDir tmp: Path) {
        val files = extract(tmp)
        files.map { it.name } shouldContainAll listOf("KtorServerRoutesKt.ws")

        val ws = files.single { it.name == "KtorServerRoutesKt.ws" }.readText()
        // Routes (paths + methods), including nesting and an inline-path verb.
        ws shouldContain "GET /health"
        ws shouldContain "GET /users"
        ws shouldContain "POST UserDto /users"
        ws shouldContain "GET /users/{id"
        ws shouldContain "DELETE /users/{id"
        // Request body recovered from call.receive<UserDto>().
        ws shouldContain "UserDto"
        // Explicit status codes from HttpStatusCode.Created / NoContent.
        ws shouldContain "201"
        ws shouldContain "204"
        // Exactly the five declared routes — no spurious prefix-less duplicates
        // from walking the synthetic config lambdas at the top level.
        ws.split("endpoint ").size - 1 shouldBe 5
        ws shouldContain "endpoint GetUsersById GET /users/{id"
        ws shouldContain "endpoint DeleteUsersById DELETE /users/{id"
    }

    @Test
    fun `extracts Ktor client calls into a ws file`(@TempDir tmp: Path) {
        val files = extract(tmp)
        files.map { it.name } shouldContainAll listOf("KtorUserClient.ws")

        val ws = files.single { it.name == "KtorUserClient.ws" }.readText()
        ws shouldContain "GET /users"
        ws shouldContain "POST UserDto /users"
        // Response/request bodies recovered from .body() and setBody().
        ws shouldContain "UserDto"
        // One endpoint per client method: listUsers, getUser, createUser.
        ws.split("endpoint ").size - 1 shouldBe 3
        ws shouldContain "endpoint ListUsers GET /users"
        ws shouldContain "endpoint CreateUser POST UserDto /users"
    }

    @Test
    fun `extracts a client whose requests come from inline reified helpers`(@TempDir tmp: Path) {
        // The inlined helpers leave two nameless anonymous classes behind, both
        // capturing the HttpRequestBuilder. They are compiler output, not
        // clients: scanning them used to abort the run with a simple-name
        // collision between two empty names.
        val files = extract(tmp)
        files.map { it.name } shouldContainAll listOf("KtorInlineReifiedClient.ws")

        // One endpoint per public method, with the reified type resolved at the
        // call site. (The helpers' own compiled copies still add a GetAll /
        // GetSingle pair — inline declarations are not yet recognised as such.)
        val ws = files.single { it.name == "KtorInlineReifiedClient.ws" }.readText()
        ws shouldContain "endpoint ListInlineUsers GET /inline/users"
        ws shouldContain "endpoint GetInlineUser GET /inline/users/single"
        ws shouldContain "UserDto[]"
    }

    @Test
    fun `extracts Ktor query and header parameters from the handler body`(@TempDir tmp: Path) {
        val files = extract(tmp)
        val ws = files.single { it.name == "KtorSearchRoutesKt.ws" }.readText()

        // Query params, with type upgraded from String via ?.toBoolean()/?.toInt().
        ws shouldContain "active: Boolean?"
        ws shouldContain "page: Integer32?"
        ws shouldContain "q: String?"
        // Header params (incl. the request.header("…") extension form).
        ws shouldContain "X-Trace"
        ws shouldContain "Authorization"
    }

    @Test
    fun `extractKtor=false skips Ktor server and client`(@TempDir tmp: Path) {
        val files = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = thisModuleClassesDirs(),
                runtimeClasspath = emptyList(),
                outputDirectory = File(tmp.toFile(), "ws").apply { mkdirs() },
                basePackage = "community.flock.wirespec.extractor.fixtures.ktor",
                extractKtor = false,
            )
        ).filesWritten
        val names = files.map { it.name }
        names.contains("KtorServerRoutesKt.ws") shouldBe false
        names.contains("KtorUserClient.ws") shouldBe false
    }
}
