package community.flock.wirespec.extractor.jar

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Bundles emitted `.ws` files into a jar for publishing. */
object WirespecJarPackager {

    private const val EPOCH_1980 = 315_532_800_000L

    /** Zip every `.ws` file under [sourceDir] into [target], each entry prefixed with [packageDir]. */
    fun pack(sourceDir: File, packageDir: String, target: File): File {
        val prefix = packageDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        target.parentFile?.mkdirs()

        val wsFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "ws" }
            .sortedBy { it.relativeTo(sourceDir).invariantSeparatorsPath }

        JarOutputStream(target.outputStream().buffered()).use { jar ->
            for (file in wsFiles) {
                jar.putNextEntry(JarEntry(prefix + file.relativeTo(sourceDir).invariantSeparatorsPath).apply { time = EPOCH_1980 })
                file.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return target
    }

    /** Slash-separated package dir for jar entries: [basePackage] if set, else [fallback]. */
    fun packagePath(basePackage: String?, fallback: String): String =
        (basePackage?.takeIf { it.isNotBlank() } ?: fallback).trim().replace('.', '/').trim('/')
}
