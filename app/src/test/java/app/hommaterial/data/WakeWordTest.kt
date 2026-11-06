package app.hommaterial.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The same made-up word as pwa/tools/test.mjs: vowel-like sounds, a little faster or slower and higher or lower each time. */
class WakeWordTest {
    private fun say(vowels: List<Int>, speed: Double, pitch: Double): DoubleArray {
        val out = ArrayList<Double>()
        var noise = 7L
        val hiss = { noise = (noise * 69069 + 1) % 4294967296; (noise / 4294967296.0 - 0.5) * 0.002 }
        repeat(WakeWord.RATE / 2) { out += hiss() }
        var t = 0
        for (formant in vowels) {
            var i = 0
            while (i < 0.12 * WakeWord.RATE / speed) {
                var x = 0.0
                var k = 1
                while (k * pitch < 4000) {
                    x += exp(-((k * pitch - formant) / 250).let { it * it }) * sin(2 * PI * k * pitch * t / WakeWord.RATE)
                    k++
                }
                out += 0.2 * x + hiss()
                i++
                t++
            }
        }
        repeat(WakeWord.RATE / 2) { out += hiss() }
        return out.toDoubleArray()
    }

    private val word = listOf(700, 1800, 500, 2200, 900)

    @Test
    fun wakeWordIsTaughtAndHeardAgainNotOtherWords() {
        val samples = listOf(0.9 to 110.0, 1.0 to 120.0, 1.05 to 125.0, 1.1 to 115.0)
            .map { (speed, pitch) -> WakeWord.trim(WakeWord.Features().push(say(word, speed, pitch)))!! }
        val model = (WakeWord.train(samples) as WakeWord.Taught.Done).model
        assertTrue(model.threshold > 0)
        val restored = WakeWord.Model.fromJson(model.toJson())!!
        fun hears(vowels: List<Int>, speed: Double, pitch: Double): Boolean {
            val features = WakeWord.Features()
            val detector = WakeWord.Detector(restored, 1)
            detector.feed(features.push(say(emptyList(), 1.0, 100.0)))
            return detector.feed(features.push(say(vowels, speed, pitch)))
        }
        assertEquals(true, hears(word, 0.95, 122.0))
        assertEquals(false, hears(listOf(2200, 500, 1800, 900, 700), 1.0, 120.0))
        assertEquals(false, hears(listOf(700, 1800), 1.0, 120.0))
        assertNull(WakeWord.trim(WakeWord.Features().push(say(emptyList(), 1.0, 100.0))))
    }
}
