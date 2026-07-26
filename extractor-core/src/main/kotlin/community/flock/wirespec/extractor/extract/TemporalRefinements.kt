package community.flock.wirespec.extractor.extract

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * Date and time types whose JSON form is a string with a fixed shape, mapped to the
 * pattern that shape satisfies. The extractor turns each into a refined `String`
 * definition instead of an unconstrained one.
 *
 * The patterns describe what Jackson writes with `JavaTimeModule` and
 * `WRITE_DATES_AS_TIMESTAMPS` disabled — Spring Boot's default. Where the ISO format has
 * optional parts they accept all of them: Jackson pads seconds (`10:15:00`) but
 * `toString()` and other producers drop them (`10:15`), and fractions range from absent
 * to nanosecond precision. A pattern that rejects a value a real service emits is worse
 * than no pattern at all.
 *
 * Left unconstrained on purpose:
 * - `Duration` — Jackson writes it as a JSON *number* of seconds (`5400.000000000`), so
 *   no string pattern describes it.
 * - `ZoneId` — a free-form region name (`Europe/Amsterdam`); a pattern would add nothing.
 * - `DayOfWeek` / `Month` — Java enums, already emitted as Wirespec enums.
 *
 * A field carrying `@JsonFormat`, or an app that sets `spring.jackson.date-format` or
 * re-enables timestamps, serializes differently; these patterns describe the defaults.
 */
internal object TemporalRefinements {

    private const val DATE = """\d{4}-\d{2}-\d{2}"""
    private const val TIME = """\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?"""
    private const val OFFSET = """(Z|[+-]\d{2}:\d{2}(:\d{2})?)"""
    /** `ZonedDateTime` appends the zone id when Jackson's `WRITE_DATES_WITH_ZONE_ID` is on. */
    private const val ZONE_ID = """(\[[A-Za-z0-9_+\-./]+\])?"""
    /** `java.util.Date` and friends go through Jackson's `StdDateFormat`: always millis + offset. */
    private const val LEGACY = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})"""

    private val patterns: Map<Class<*>, String> = mapOf(
        LocalDate::class.java to DATE,
        LocalTime::class.java to TIME,
        LocalDateTime::class.java to "${DATE}T$TIME",
        Instant::class.java to "${DATE}T${TIME}Z",
        OffsetTime::class.java to "$TIME$OFFSET",
        OffsetDateTime::class.java to "${DATE}T$TIME$OFFSET",
        ZonedDateTime::class.java to "${DATE}T$TIME$OFFSET$ZONE_ID",
        YearMonth::class.java to """\d{4}-\d{2}""",
        MonthDay::class.java to """--\d{2}-\d{2}""",
        // Year pads to four digits and signs anything wider ("+12345").
        Year::class.java to """[+-]?\d{4,}""",
        ZoneOffset::class.java to OFFSET,
        // Every component of an ISO period is optional — Period.ZERO prints "P0D".
        Period::class.java to """P(-?\d+Y)?(-?\d+M)?(-?\d+D)?""",
        // Pre-java.time types, still common in older codebases.
        Date::class.java to LEGACY,
        Calendar::class.java to LEGACY,
        GregorianCalendar::class.java to LEGACY,
        java.sql.Timestamp::class.java to LEGACY,
        java.sql.Date::class.java to DATE,
        java.sql.Time::class.java to """\d{2}:\d{2}:\d{2}""",
    )

    /** The anchored regex for [cls], or null when its wire shape is not constrained. */
    fun regexFor(cls: Class<*>): String? = patterns[cls]?.let { "^$it\$" }
}
