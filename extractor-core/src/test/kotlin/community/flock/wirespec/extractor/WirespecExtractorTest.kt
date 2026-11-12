package community.flock.wirespec.extractor

import community.flock.wirespec.extractor.scan.ClassCounter
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WirespecExtractorTest {

    /** Locate the test-classes directory of this module on the runtime classpath. */
    private fun thisModuleClassesDir(): File {
        // Any fixture class works as a probe; HelloController is small and stable.
        val probe = community.flock.wirespec.extractor.fixtures.HelloController::class.java
        val classFileUrl = probe.protectionDomain.codeSource.location
        return File(classFileUrl.toURI())
    }

    /**
     * Locate every test-classes output directory of this module (Kotlin + Java).
     * Necessary because Gradle splits Java and Kotlin output into separate
     * directories and the extractor scans by URLClassLoader, not the JVM
     * classpath of the test runner.
     */
    private fun thisModuleClassesDirs(): List<File> {
        val kotlinDir = thisModuleClassesDir()
        // Sibling: .../classes/kotlin/test  →  .../classes/java/test
        val javaDir = kotlinDir.parentFile?.parentFile?.resolve("java/test")
        return listOfNotNull(kotlinDir, javaDir?.takeIf { it.exists() })
    }

    @Test
    fun `extract writes ws files for known controllers and returns counts`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(thisModuleClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
            )
        )

        result.controllerCount shouldBe (out.listFiles()!!.filter { it.name != "types.ws" }.size)
        result.filesWritten.map { it.name } shouldContainAll listOf("HelloController.ws")
        result.filesWritten.all { it.exists() } shouldBe true
    }

    @Test
    fun `extract emits ws files for Spring functional DSL configuration classes`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = thisModuleClassesDirs(),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.dsl",
            )
        )

        val names = result.filesWritten.map { it.name }
        names shouldContainAll listOf(
            "WebFluxRouterConfig.ws",
            "WebFluxCoRouterConfig.ws",
            "MvcRouterConfig.ws",
            "JavaWebFluxRouterConfig.ws",
        )
        // Sanity-check at least one DSL config's contents include the expected route + method.
        val webfluxWs = result.filesWritten.single { it.name == "WebFluxRouterConfig.ws" }
        val text = webfluxWs.readText()
        text shouldContain "GET /users/{id"
        text shouldContain "DELETE /users/{id"
        // Java DSL also lands in its own .ws file (separate Gradle output dir).
        val javaWs = result.filesWritten.single { it.name == "JavaWebFluxRouterConfig.ws" }
        javaWs.readText() shouldContain "/things"
    }

    @Test
    fun `extractSpring=false skips Spring controllers`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val result = WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(thisModuleClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
                extractSpring = false,
            )
        )
        result.filesWritten.map { it.name }.contains("HelloController.ws") shouldBe false
    }

    @Test
    fun `extract throws WirespecExtractorException when classes directory is missing`(@TempDir tmp: Path) {
        val missing = File(tmp.toFile(), "does-not-exist")
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }

        val ex = assertThrows<WirespecExtractorException> {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectories = listOf(missing),
                    runtimeClasspath = emptyList(),
                    outputDirectory = out,
                )
            )
        }
        ex.message!! shouldContain "No compiled classes in"
        ex.message!! shouldContain "Did compilation run before extraction?"
    }

    @Test
    fun `extract routes log messages through the provided ExtractLog`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val log = object : ExtractLog {
            override fun info(msg: String) { infos += msg }
            override fun warn(msg: String) { warns += msg }
        }

        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(thisModuleClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
                log = log,
            )
        )

        val scanned = ClassCounter.count(listOf(thisModuleClassesDir()), "community.flock.wirespec.extractor.fixtures")
        infos.first() shouldBe
            "Scanned $scanned class(es) under community.flock.wirespec.extractor.fixtures in ${thisModuleClassesDir().absolutePath}"
        infos.single { it.startsWith("Found ") && it.contains(" Spring controller(s): ") } shouldContain "HelloController"
        infos.any { it.startsWith("Extracted ") && it.contains(" endpoint(s), ") && it.endsWith(" type(s)") } shouldBe true
        infos.any { it.startsWith("Wrote ") && it.contains(".ws file(s) to") } shouldBe true
    }

    @Test
    fun `extract always logs the categories in which nothing was found`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        val infos = mutableListOf<String>()
        val log = object : ExtractLog {
            override fun info(msg: String) { infos += msg }
            override fun warn(msg: String) {}
        }

        // Only functional-DSL configurations live in this package.
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = thisModuleClassesDirs(),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures.dsl",
                log = log,
            )
        )

        infos.single { it.startsWith("Found ") && it.contains(" functional-DSL configuration(s): ") } shouldContain
            "MvcRouterConfig"
        infos.single { it.startsWith("Found no ") } shouldBe
            "Found no Spring controller(s), JAX-RS resource(s), Ktor routing configuration(s), Ktor client(s), " +
            "GraphQL controller(s), messaging listener method(s), messaging producer method(s)"
    }

    @Test
    fun `ClassCounter counts every compiled class under the base package`() {
        val dir = thisModuleClassesDir()
        val pkg = "community.flock.wirespec.extractor.fixtures.dto"
        val onDisk = File(dir, pkg.replace('.', '/')).walk().count { it.isFile && it.extension == "class" }

        ClassCounter.count(listOf(dir), pkg) shouldBe onDisk
        ClassCounter.count(listOf(dir), null) shouldBeGreaterThan onDisk
        ClassCounter.count(listOf(File(dir, "does-not-exist")), null) shouldBe 0
    }

    @Test
    fun `ExtractLog NoOp is the default and is safe`(@TempDir tmp: Path) {
        val out = File(tmp.toFile(), "ws").apply { mkdirs() }
        // Just verify the default is wired without throwing.
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(thisModuleClassesDir()),
                runtimeClasspath = emptyList(),
                outputDirectory = out,
                basePackage = "community.flock.wirespec.extractor.fixtures",
            )
        )
    }

    @Test
    fun `rawGenericReference factory produces an actionable message`() {
        val ex = WirespecExtractorException.rawGenericReference(
            rawClass = "com.example.Page",
            controllerMethod = "UserController.list",
        )
        ex.message!! shouldContain "Cannot extract raw generic type Page"
        ex.message!! shouldContain "UserController.list"
        ex.message!! shouldContain "provide a concrete type argument"
    }

    @Test
    fun `wildcardArgument factory produces an actionable message`() {
        val ex = WirespecExtractorException.wildcardArgument(
            atType = "Page<?>",
            controllerMethod = "UserController.list",
        )
        ex.message!! shouldContain "Wildcard type argument in Page<?>"
        ex.message!! shouldContain "UserController.list"
        ex.message!! shouldContain "replace with a concrete type"
    }

    @Test
    fun `rawGenericSuperclass factory produces an actionable message`() {
        val ex = WirespecExtractorException.rawGenericSuperclass(
            subclassName = "UserPage",
            rawSuperclassName = "Page",
        )
        ex.message!! shouldContain "UserPage extends generic Page"
        ex.message!! shouldContain "Page<UserDto>"
    }
}
