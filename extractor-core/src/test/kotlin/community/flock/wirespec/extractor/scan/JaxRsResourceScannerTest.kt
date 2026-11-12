package community.flock.wirespec.extractor.scan

import community.flock.wirespec.extractor.fixtures.jaxrsplain.PlainResource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

class JaxRsResourceScannerTest {

    private val pkg = PlainResource::class.java.packageName

    @Test
    fun `finds a resource whose JAX-RS API is on the classpath`() {
        val warnings = mutableListOf<String>()

        val found = JaxRsResourceScanner.scan(javaClass.classLoader, listOf(pkg), pkg, warnings::add)

        found.map { it.name } shouldContainExactly listOf(PlainResource::class.java.name)
        warnings.shouldBeEmpty()
    }

    @Test
    fun `warns about and skips a resource whose @Path annotation class is not on the classpath`() {
        // The test classes alone, without jakarta.ws.rs-api: the class loads, but Java drops
        // its @Path and @GET annotations because their classes can't be loaded.
        val testClasses = PlainResource::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(testClasses), ClassLoader.getPlatformClassLoader()).use { loader ->
            val warnings = mutableListOf<String>()

            val found = JaxRsResourceScanner.scan(loader, listOf(pkg), pkg, warnings::add)

            found.shouldBeEmpty()
            warnings.size shouldBe 1
            warnings.single() shouldContain "Skipping JAX-RS resource ${PlainResource::class.java.name}, " +
                "so its endpoints are missing from the contract"
            warnings.single() shouldContain "its @Path annotation can't be read, because annotation class " +
                "jakarta.ws.rs.Path is not on the extraction classpath"
            warnings.single() shouldContain "add the jar that contains jakarta.ws.rs.Path"
        }
    }
}
