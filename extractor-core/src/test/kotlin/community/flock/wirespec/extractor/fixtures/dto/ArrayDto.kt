package community.flock.wirespec.extractor.fixtures.dto

/** Arrays serialize as JSON lists, exactly like Collections. */
data class ArrayDto(
    val names: Array<String>,
    val ids: Array<String>? = null,
    val counts: IntArray,
    val roles: Array<Role>,
    val payload: ByteArray,
    val matrix: Array<List<String>>,
)
