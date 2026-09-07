package community.flock.wirespec.extractor.fixtures.dto

/**
 * Delegated properties: the Kotlin compiler stores the delegate itself in a private
 * `<name>$delegate` field (`kotlin.Lazy`, a `ReadWriteProperty`, …) that is not flagged
 * synthetic, so extraction has to read the property off its accessor instead.
 */
abstract class Metric {
    abstract val impressions: Int

    val clickThroughRate by lazy {
        if (impressions > 0) 1.0 / impressions else 0.0
    }
}

data class Report(
    override val impressions: Int,
    val label: String,
) : Metric()

class DelegatedDto(
    val id: String,
) {
    /** Non-null reference type: nullability must come from the accessor. */
    val slug: String by lazy { id.lowercase() }

    /** Nullable delegated property. */
    val alias: String? by lazy { null }

    /** `is`-prefixed property: the accessor is `isPublished()`, not `getIsPublished()`. */
    val isPublished: Boolean by lazy { id.isNotEmpty() }

    /** Not readable from outside, and therefore not part of the wire shape. */
    private val secret: String by lazy { id }

    override fun toString(): String = "DelegatedDto($id, $secret)"
}
