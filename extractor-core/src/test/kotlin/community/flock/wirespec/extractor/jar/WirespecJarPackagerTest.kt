package community.flock.wirespec.extractor.jar

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile

class WirespecJarPackagerTest {

    @Test
    fun `packagePath prefers basePackage and converts dots to slashes`() {
        WirespecJarPackager.packagePath("com.acme.api", fallback = "x") shouldBe "com/acme/api"
    }

    @Test
    fun `packagePath falls back when basePackage is null or blank`() {
        WirespecJarPackager.packagePath(null, fallback = "com.acme") shouldBe "com/acme"
        WirespecJarPackager.packagePath("  ", fallback = "com.acme") shouldBe "com/acme"
    }

    @Test
    fun `pack places ws files under the package directory and ignores non-ws`(@TempDir tmp: Path) {
        val src = File(tmp.toFile(), "wirespec").apply { mkdirs() }
        File(src, "UserController.ws").writeText("endpoint GetUser GET /users -> Unit")
        File(src, "types.ws").writeText("type UserDto { id: String }")
        File(src, "README.txt").writeText("ignore me")

        val jar = WirespecJarPackager.pack(src, "com/acme/api", File(tmp.toFile(), "out.jar"))

        JarFile(jar).use { jf ->
            val names = jf.entries().asSequence().map { it.name }.sorted().toList()
            names shouldContainExactly listOf(
                "com/acme/api/UserController.ws",
                "com/acme/api/types.ws",
            )
        }
    }

    @Test
    fun `pack is reproducible - identical inputs yield byte-identical jars`(@TempDir tmp: Path) {
        val src = File(tmp.toFile(), "wirespec").apply { mkdirs() }
        File(src, "types.ws").writeText("type UserDto { id: String }")

        val a = WirespecJarPackager.pack(src, "com/acme", File(tmp.toFile(), "a.jar"))
        val b = WirespecJarPackager.pack(src, "com/acme", File(tmp.toFile(), "b.jar"))

        a.readBytes().toList() shouldBe b.readBytes().toList()
    }

    @Test
    fun `blank package places entries at the jar root`(@TempDir tmp: Path) {
        val src = File(tmp.toFile(), "wirespec").apply { mkdirs() }
        File(src, "types.ws").writeText("type UserDto { id: String }")

        val jar = WirespecJarPackager.pack(src, "", File(tmp.toFile(), "root.jar"))

        JarFile(jar).use { jf ->
            jf.entries().asSequence().map { it.name }.toList() shouldContainExactly listOf("types.ws")
        }
    }
}
