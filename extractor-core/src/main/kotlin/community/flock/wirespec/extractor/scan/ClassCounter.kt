package community.flock.wirespec.extractor.scan

import io.github.classgraph.ClassGraph
import java.io.File

/**
 * Counts the compiled classes of the project itself — the classes directories
 * handed to the extractor, restricted to `basePackage` the same way the
 * scanners restrict their results. Dependency jars on the runtime classpath
 * are not counted.
 */
internal object ClassCounter {

    fun count(classesDirectories: List<File>, basePackage: String?): Int {
        val dirs = classesDirectories.filter { it.isDirectory }
        if (dirs.isEmpty()) return 0
        val graph = ClassGraph()
            .overrideClasspath(dirs)
            .enableClassInfo()
        if (basePackage != null) graph.acceptPackages(basePackage)
        return graph.scan().use { it.allClasses.size }
    }
}
