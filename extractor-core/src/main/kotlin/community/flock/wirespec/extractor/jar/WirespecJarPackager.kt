package community.flock.wirespec.extractor.jar

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Bundles emitted `.ws` files into a jar for publishing.
 *
 * The `.ws` files themselves share one global namespace (Wirespec type names are
 * globally unique across files), but their *file paths* are not: two independent
 * apps each emit a `types.ws` and, say, a `UserController.ws`. When several such
 * jars land on one classpath those paths would collide, so every entry is placed
 * under a per-project **package directory** — see [packagePath].
 */
object WirespecJarPackager {

    // Fixed 1980-01-01 UTC entry timestamp so a given set of inputs always
    // produces a byte-identical jar (reproducible builds; avoids re-publishing
    // an artifact that only differs by embedded mtimes).
    private const val FIXED_ENTRY_TIME = 315_532_800_000L

    /**
     * Write every `.ws` file found under [sourceDir] (recursively) into [target],
     * placing each entry under [packageDir] (a slash-separated directory prefix,
     * e.g. `com/acme/api`). Blank [packageDir] puts entries at the jar root.
     *
     * Creates [target]'s parent directories as needed and overwrites any existing
     * file. Returns [target].
     */
    fun pack(sourceDir: File, packageDir: String, target: File): File {
        val prefix = packageDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        target.parentFile?.mkdirs()

        val wsFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "ws" }
            .sortedBy { it.relativeTo(sourceDir).invariantSeparatorsPath }
            .toList()

        JarOutputStream(target.outputStream().buffered()).use { jar ->
            for (file in wsFiles) {
                val entryName = prefix + file.relativeTo(sourceDir).invariantSeparatorsPath
                jar.putNextEntry(JarEntry(entryName).apply { time = FIXED_ENTRY_TIME })
                file.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return target
    }

    /**
     * Resolve the package directory for jar entries. Prefers [basePackage] (the
     * same scoping package the user already configured), falling back to
     * [fallback] (typically the project's group/artifact coordinates) when
     * [basePackage] is null or blank — so the prefix is never empty and jars from
     * different projects never collide by path.
     */
    fun packagePath(basePackage: String?, fallback: String): String =
        (basePackage?.takeIf { it.isNotBlank() } ?: fallback)
            .trim()
            .replace('.', '/')
            .trim('/')
}
