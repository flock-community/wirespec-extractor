package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.DefaultValue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class KotlinDefaultsTest {

    enum class Color { RED, GREEN }

    @Suppress("unused")
    data class Literals(
        val required: String,
        val int: Int = 42,
        val negative: Int = -7,
        val big: Int = 100_000,
        val long: Long = 5_000_000_000L,
        val zeroLong: Long = 0L,
        val double: Double = 4.5,
        val float: Float = 1.5f,
        val flag: Boolean = true,
        val off: Boolean = false,
        val text: String = "hello",
        val empty: String = "",
        val color: Color = Color.GREEN,
        val boxed: Int? = 3,
        val boxedFlag: Boolean? = false,
        val nothing: String? = null,
        val computed: List<String> = listOf("a"),
        val now: Instant = Instant.EPOCH,
    )

    @Test
    fun `reads literal constructor defaults by parameter name`() {
        KotlinDefaults.of(Literals::class.java) shouldBe mapOf(
            "int" to DefaultValue.IntegerValue(42),
            "negative" to DefaultValue.IntegerValue(-7),
            "big" to DefaultValue.IntegerValue(100_000),
            "long" to DefaultValue.IntegerValue(5_000_000_000L),
            "zeroLong" to DefaultValue.IntegerValue(0),
            "double" to DefaultValue.NumberValue(4.5),
            "float" to DefaultValue.NumberValue(1.5),
            "flag" to DefaultValue.BooleanValue(true),
            "off" to DefaultValue.BooleanValue(false),
            "text" to DefaultValue.StringValue("hello"),
            "empty" to DefaultValue.StringValue(""),
            "color" to DefaultValue.EnumValue("GREEN"),
            "boxed" to DefaultValue.IntegerValue(3),
            "boxedFlag" to DefaultValue.BooleanValue(false),
        )
    }

    @Suppress("unused")
    data class Wide(
        val p0: Int = 0,
        val p1: Int = 1,
        val p2: Int = 2,
        val p3: Int = 3,
        val p4: Int = 4,
        val p5: Int = 5,
        val p6: Int = 6,
        val p7: Int = 7,
        val p8: Int = 8,
        val p9: Int = 9,
        val p10: Int = 10,
        val p11: Int = 11,
        val p12: Int = 12,
        val p13: Int = 13,
        val p14: Int = 14,
        val p15: Int = 15,
        val p16: Int = 16,
        val p17: Int = 17,
        val p18: Int = 18,
        val p19: Int = 19,
        val p20: Int = 20,
        val p21: Int = 21,
        val p22: Int = 22,
        val p23: Int = 23,
        val p24: Int = 24,
        val p25: Int = 25,
        val p26: Int = 26,
        val p27: Int = 27,
        val p28: Int = 28,
        val p29: Int = 29,
        val p30: Int = 30,
        val p31: Int = 31,
        val p32: Int = 32,
        val p33: Int = 33,
    )

    @Test
    fun `reads defaults past the first 32 parameters from the second mask`() {
        val defaults = KotlinDefaults.of(Wide::class.java)
        defaults.size shouldBe 34
        defaults["p0"] shouldBe DefaultValue.IntegerValue(0)
        defaults["p31"] shouldBe DefaultValue.IntegerValue(31)
        defaults["p33"] shouldBe DefaultValue.IntegerValue(33)
    }

    data class NoDefaults(val a: String, val b: Int)

    @Test
    fun `a class without defaults or a Java class has none`() {
        KotlinDefaults.of(NoDefaults::class.java).shouldBeEmpty()
        KotlinDefaults.of(java.util.ArrayList::class.java).shouldBeEmpty()
    }
}
