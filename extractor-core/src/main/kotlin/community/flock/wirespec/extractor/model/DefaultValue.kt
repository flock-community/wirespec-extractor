package community.flock.wirespec.extractor.model

/**
 * A literal default value of a field — what a missing input property is bound to.
 * Mirrors the shape of Wirespec's own `DefaultValue` (wirespec PR #711), so the two
 * map one-to-one once the extractor emits that syntax natively.
 */
sealed interface DefaultValue {
    /** The value as written in a Wirespec annotation argument. */
    val literal: String

    data class StringValue(val value: String) : DefaultValue {
        override val literal: String get() = value
    }

    data class IntegerValue(val value: Long) : DefaultValue {
        override val literal: String get() = value.toString()
    }

    data class NumberValue(val value: Double) : DefaultValue {
        override val literal: String get() = value.toString()
    }

    data class BooleanValue(val value: Boolean) : DefaultValue {
        override val literal: String get() = value.toString()
    }

    /** An entry of the enum the field refers to. */
    data class EnumValue(val value: String) : DefaultValue {
        override val literal: String get() = value
    }
}
