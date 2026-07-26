package community.flock.wirespec.extractor.extract

/**
 * Helpers for reading names off Kotlin-compiled members.
 */
object KotlinNames {

    /**
     * Strip Kotlin's value-class name mangling.
     *
     * A function whose signature mentions a `@JvmInline value class` is compiled with a
     * `-<hash>` suffix — `fun getUser(id: UserId)` becomes `getUser-_mjp9w0` — so that
     * overloads stay distinct once the wrapper erases to its underlying type. Neither
     * Java nor Kotlin allow `-` in a source method name, so the suffix can be cut off
     * unambiguously, leaving the name the developer actually wrote. That is what belongs
     * in a generated Wirespec identifier; the mangled form isn't even valid there.
     */
    fun demangle(methodName: String): String = methodName.substringBefore('-')
}
