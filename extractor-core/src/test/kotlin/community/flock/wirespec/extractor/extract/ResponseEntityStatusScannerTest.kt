// src/test/kotlin/community/flock/wirespec/extractor/extract/ResponseEntityStatusScannerTest.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.fixtures.status.StatusController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResponseEntityStatusScannerTest {

    private fun method(name: String) =
        StatusController::class.java.methods.first { it.name == name }

    @Test
    fun `status(HttpStatus CREATED) is read as 201`() {
        ResponseEntityStatusScanner.scan(method("createViaStatusEnum")) shouldBe 201
    }

    @Test
    fun `created() factory is read as 201`() {
        ResponseEntityStatusScanner.scan(method("createViaFactory")) shouldBe 201
    }

    @Test
    fun `status(201) numeric literal is read as 201`() {
        ResponseEntityStatusScanner.scan(method("createViaStatusInt")) shouldBe 201
    }

    @Test
    fun `ResponseEntity constructor with HttpStatus is read as 201`() {
        ResponseEntityStatusScanner.scan(method("createViaConstructor")) shouldBe 201
    }

    @Test
    fun `suspend handler building a CREATED ResponseEntity is read as 201`() {
        ResponseEntityStatusScanner.scan(method("createSuspend")) shouldBe 201
    }

    @Test
    fun `open suspend handler building a CREATED ResponseEntity is read as 201`() {
        ResponseEntityStatusScanner.scan(method("createOpenSuspend")) shouldBe 201
    }

    /**
     * Guards the fixture, not the scanner: if Kotlin ever stopped splitting an
     * `open` suspend function into a `$suspendImpl`, the test above would pass
     * for the wrong reason and stop covering the all-open shape.
     */
    @Test
    fun `open suspend handler really compiles to a separate suspendImpl body`() {
        StatusController::class.java.declaredMethods
            .any { it.name == "createOpenSuspend\$suspendImpl" } shouldBe true
    }

    @Test
    fun `ok() factory stays 200`() {
        ResponseEntityStatusScanner.scan(method("okStays200")) shouldBe 200
    }

    @Test
    fun `noContent() factory is read as 204`() {
        ResponseEntityStatusScanner.scan(method("deleteViaNoContent")) shouldBe 204
    }

    @Test
    fun `end-to-end extraction emits 201 for a programmatically-created response`() {
        val endpoints = EndpointExtractor(TypeExtractor()).extract(StatusController::class.java)
        val created = endpoints.single { it.name == "CreateViaStatusEnum" }
        created.responses.single().statusCode shouldBe 201
        val suspend = endpoints.single { it.name == "CreateSuspend" }
        suspend.responses.single().statusCode shouldBe 201
        val openSuspend = endpoints.single { it.name == "CreateOpenSuspend" }
        openSuspend.responses.single().statusCode shouldBe 201
        val noContent = endpoints.single { it.name == "DeleteViaNoContent" }
        noContent.responses.single().statusCode shouldBe 204
        noContent.responses.single().body shouldBe null
    }
}
