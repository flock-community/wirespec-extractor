package community.flock.wirespec.extractor.fixtures.dto

@JvmInline
value class UserId(val value: String)

@JvmInline
value class Score(val value: Int)

/** Value class wrapping another value class — flattening must go all the way down. */
@JvmInline
value class AccountId(val value: UserId)

/** Nullable underlying value: every use of the wrapper is nullable. */
@JvmInline
value class Nickname(val value: String?)

/** Underlying value is itself a DTO, which must still be registered as a definition. */
@JvmInline
value class Owner(val value: UserDto)

/** Underlying value is a collection. */
@JvmInline
value class Tags(val value: List<String>)

@JvmInline
value class Boxed<T>(val value: T)

data class ValueClassDto(
    val id: UserId,
    val score: Score,
    val account: AccountId,
    val nickname: Nickname,
    val alias: UserId?,
    val ids: List<UserId>,
    // A generic value class erases to bare `Object` at a field position, but survives
    // as a generic argument.
    val boxes: List<Boxed<Score>>,
    val container: Container<UserId>,
)
