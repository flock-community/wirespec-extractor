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

    /**
     * The suffix the Kotlin compiler appends to the private backing field it generates
     * for a delegated property (`val rate by lazy { … }` becomes `rate$delegate`).
     */
    private const val DELEGATE_SUFFIX = "\$delegate"

    /**
     * The logical property name behind a delegate backing field, or null when [fieldName]
     * is not one.
     *
     * The delegate field holds the delegate instance (`kotlin.Lazy`, a `ReadWriteProperty`,
     * …), never the property's value, and it is not flagged `ACC_SYNTHETIC`, so it has to be
     * recognised by name. The property itself is only reachable through its accessor.
     */
    fun delegatedPropertyName(fieldName: String): String? =
        fieldName.removeSuffix(DELEGATE_SUFFIX).takeIf { it != fieldName && it.isNotEmpty() }

    /**
     * The property names a zero-argument accessor could stand for, most specific first.
     *
     * `getRate()` backs `rate`; `isActive()` backs the Kotlin property `isActive` and the
     * JavaBean property `active`, and both spellings are accepted since the two conventions
     * disagree. Returns empty for a method that is not an accessor at all.
     */
    fun accessorPropertyNames(methodName: String): List<String> {
        val name = demangle(methodName)
        return when {
            name.startsWith("get") && name.length > 3 -> listOf(name.removePrefix("get").decapitalize())
            name.startsWith("is") && name.length > 2 -> listOf(name, name.removePrefix("is").decapitalize())
            else -> emptyList()
        }
    }

    private fun String.decapitalize(): String = replaceFirstChar { it.lowercase() }
}
