package community.flock.wirespec.extractor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class SkipWarningsTest {

    @Test
    fun `names the missing class in dotted form, the error, and how to fix it`() {
        val warning = SkipWarnings.skipping(
            "JAX-RS resource", "com.acme.ReadinessEndpoint", NoClassDefFoundError("jakarta/ws/rs/core/Response"),
        )

        warning shouldBe "Skipping JAX-RS resource com.acme.ReadinessEndpoint, so its endpoints are missing " +
            "from the contract: class jakarta.ws.rs.core.Response is not on the extraction classpath " +
            "(NoClassDefFoundError). The extractor loads the compiled classes plus every dependency the " +
            "module compiles or runs with; add the jar that contains jakarta.ws.rs.core.Response to the " +
            "module's dependencies, or to the extractor plugin's."
    }

    @Test
    fun `finds a missing class among the causes`() {
        val wrapped = IllegalArgumentException(
            "Could not load class com.acme.Foo",
            ClassNotFoundException("com.acme.Bar"),
        )

        SkipWarnings.cause(wrapped) shouldStartWith
            "class com.acme.Bar is not on the extraction classpath (ClassNotFoundException)."
    }

    @Test
    fun `reads the type of a TypeNotPresentException`() {
        SkipWarnings.cause(TypeNotPresentException("com.acme.Gone", null)) shouldStartWith
            "class com.acme.Gone is not on the extraction classpath (TypeNotPresentException)."
    }

    @Test
    fun `drops the detail the JVM appends to a class name`() {
        SkipWarnings.cause(NoClassDefFoundError("com/acme/Foo (wrong name: com/acme/foo)")) shouldStartWith
            "class com.acme.Foo is not on the extraction classpath"
    }

    @Test
    fun `does not call a class that failed to initialize missing`() {
        val cause = SkipWarnings.cause(NoClassDefFoundError("Could not initialize class com.acme.Foo"))

        cause shouldStartWith "java.lang.NoClassDefFoundError: Could not initialize class com.acme.Foo"
        cause shouldNotContain "not on the extraction classpath"
    }

    @Test
    fun `gives the exception type, where it was thrown, and its root cause`() {
        val failure = try {
            throw IllegalStateException("outer", RuntimeException("middle", UnsupportedOperationException("inner")))
        } catch (e: IllegalStateException) {
            e
        }

        val cause = SkipWarnings.cause(failure)

        cause shouldStartWith "java.lang.IllegalStateException: outer (at SkipWarningsTest."
        cause shouldContain "(SkipWarningsTest.kt:"
        cause shouldContain ", caused by java.lang.UnsupportedOperationException: inner"
        cause shouldNotContain "middle"
    }

    @Test
    fun `leaves out an empty message`() {
        SkipWarnings.cause(NullPointerException()) shouldStartWith "java.lang.NullPointerException (at "
    }
}
