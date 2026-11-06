package app.hommaterial.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The wake word ("Giacomino"), recognized in the way of "Ok Google": the user teaches it by saying
 * it a few times, each recording becomes a template of MFCC frames, and the microphone is compared
 * against them all the time with dynamic time warping. Nothing leaves the device. The same steps,
 * with the same numbers, are in pwa/web/js/wake.js.
 */
object WakeWord {
    const val RATE = 16000
    const val SAMPLES = 4
    private const val FRAME = 400 // 25 ms
    private const val HOP = 160 // 10 ms
    private const val FFT = 512
    private const val MELS = 26
    private const val CEPS = 12

    /** A frame is speech when it is this much louder than the background. */
    internal const val SPEECH_DB = 10.0

    /** How far the threshold drawn from the samples is stretched, from the strictest to the most lenient. */
    val SENSITIVITY = doubleArrayOf(1.1, 1.25, 1.4)

    private val HAMMING = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
    private fun mel(hz: Double) = 2595 * log10(1 + hz / 700)
    private fun hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)

    // Triangles over the bins of the spectrum, evenly spaced in mels from 20 Hz to half the rate.
    private val BANK: Array<DoubleArray> = run {
        val points = DoubleArray(MELS + 2) { hz(mel(20.0) + (mel(RATE / 2.0) - mel(20.0)) * it / (MELS + 1)) * FFT / RATE }
        Array(MELS) { f ->
            val (a, b, c) = Triple(points[f], points[f + 1], points[f + 2])
            DoubleArray(FFT / 2 + 1) { k -> if (k > a && k < c) (if (k <= b) (k - a) / (b - a) else (c - k) / (c - b)) else 0.0 }
        }
    }
    private val DCT = Array(CEPS) { c -> DoubleArray(MELS) { m -> cos(PI * (c + 1) * (m + 0.5) / MELS) } }

    /** A frame: its cepstral coefficients and how loud it is over the background, in dB. */
    class Frame(val v: DoubleArray, val rel: Double)

    /** The templates the user taught and the score the least typical of them reached. */
    class Model(val templates: List<Array<DoubleArray>>, val threshold: Double) {
        fun toJson(): String = JSONObject()
            .put("threshold", threshold)
            .put("templates", JSONArray(templates.map { t -> JSONArray(t.map { JSONArray(it.toList()) }) }))
            .toString()

        companion object {
            fun fromJson(json: String?): Model? = runCatching {
                val o = JSONObject(json!!)
                val list = o.getJSONArray("templates")
                Model(
                    List(list.length()) { i ->
                        val t = list.getJSONArray(i)
                        Array(t.length()) { j -> t.getJSONArray(j).let { f -> DoubleArray(f.length()) { f.getDouble(it) } } }
                    },
                    o.getDouble("threshold"),
                )
            }.getOrNull()
        }
    }

    /** In-place radix-2 FFT of [re] and [im]. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(angle * k)
                    val wi = sin(angle * k)
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    /**
     * Turns a stream of samples at [RATE] into frames: 12 cepstral coefficients and how loud the frame
     * is over the background. No running mean is taken off them: one that has not settled yet would make
     * the first recordings unlike what is heard later, and the microphone is the same one anyway.
     */
    class Features {
        private var pending = DoubleArray(0)
        private val recent = ArrayDeque<Double>()

        fun push(samples: DoubleArray): List<Frame> {
            val all = pending + samples
            val frames = mutableListOf<Frame>()
            var at = 0
            while (at + FRAME <= all.size) {
                frames += frame(all, at)
                at += HOP
            }
            pending = all.copyOfRange(at, all.size)
            return frames
        }

        private fun frame(x: DoubleArray, at: Int): Frame {
            val re = DoubleArray(FFT)
            val im = DoubleArray(FFT)
            var power = 0.0
            // Loudness from the plain samples, the spectrum from the pre-emphasized ones.
            for (i in 0 until FRAME) {
                re[i] = (x[at + i] - 0.97 * x[at + if (i > 0) i - 1 else 0]) * HAMMING[i]
                power += x[at + i] * x[at + i]
            }
            val db = 10 * log10(power / FRAME + 1e-10)
            fft(re, im)
            val spectrum = DoubleArray(FFT / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
            val logs = DoubleArray(MELS) { f ->
                var sum = 0.0
                for (k in spectrum.indices) sum += BANK[f][k] * spectrum[k]
                ln(sum + 1e-10)
            }
            val v = DoubleArray(CEPS) { c -> var sum = 0.0; for (m in 0 until MELS) sum += DCT[c][m] * logs[m]; sum }
            // Digital silence, as microphones give while they start, is not the background of the room.
            if (db < -90) return Frame(v, 0.0)
            // The background is the quietest frame of the last two seconds: a word is shorter than that.
            recent.addLast(db)
            if (recent.size > 200) recent.removeFirst()
            return Frame(v, db - recent.min())
        }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (c in a.indices) sum += (a[c] - b[c]).let { it * it }
        return sqrt(sum)
    }

    /**
     * How far [template] is from its best match in [frames], which may start and end anywhere in them
     * as long as it is not much shorter or longer than the template: the cost of the warping path over
     * its length. Infinity when nothing fits.
     */
    fun match(template: Array<DoubleArray>, frames: List<Frame>): Double {
        val n = template.size
        val m = frames.size
        var cost = DoubleArray(m) { distance(template[0], frames[it].v) }
        var start = IntArray(m) { it }
        for (i in 1 until n) {
            val next = DoubleArray(m)
            val from = IntArray(m)
            for (j in 0 until m) {
                var best = cost[j]
                var origin = start[j]
                if (j > 0 && cost[j - 1] < best) { best = cost[j - 1]; origin = start[j - 1] }
                if (j > 0 && next[j - 1] < best) { best = next[j - 1]; origin = from[j - 1] }
                next[j] = best + distance(template[i], frames[j].v)
                from[j] = origin
            }
            cost = next
            start = from
        }
        var best = Double.POSITIVE_INFINITY
        for (j in 0 until m) {
            val length = j - start[j] + 1
            if (length < 0.6 * n || length > 1.7 * n) continue
            var speech = 0
            for (k in start[j]..j) if (frames[k].rel > SPEECH_DB) speech++
            // Silence or a hum is never the word, however its shape happens to fit.
            if (speech < 0.3 * length) continue
            best = min(best, cost[j] / (n + length))
        }
        return best
    }

    /** The two closest templates together, so that one lucky match is not enough. */
    private fun score(templates: List<Array<DoubleArray>>, frames: List<Frame>): Double {
        val found = templates.map { match(it, frames) }.sorted()
        return if (found.size > 1) (found[0] + found[1]) / 2 else found[0]
    }

    /** The speech in a recording, with a little silence around it; null when there is none or too much. */
    fun trim(frames: List<Frame>): List<Frame>? {
        val first = frames.indexOfFirst { it.rel > SPEECH_DB }
        if (first < 0) return null
        val last = frames.indexOfLast { it.rel > SPEECH_DB }
        if (last - first < 25 || last - first > 200) return null
        return frames.subList(max(0, first - 5), min(frames.size, last + 6))
    }

    /** What teaching gave: a model, or the index of a sample to say again because it is far from all the rest. */
    sealed interface Taught {
        class Done(val model: Model) : Taught
        class Again(val index: Int) : Taught
    }

    /** Builds the model from the recordings, each the frames of a [trim]: the threshold is the score of the sample least like the others. */
    fun train(samples: List<List<Frame>>): Taught {
        val templates = samples.map { frames -> Array(frames.size) { i -> DoubleArray(CEPS) { round(frames[i].v[it] * 1000) / 1000 } } }
        val scores = samples.mapIndexed { i, frames -> score(templates.filterIndexed { k, _ -> k != i }, frames) }
        val sorted = scores.sorted()
        val median = sorted[sorted.size / 2]
        val worst = sorted.last()
        if (worst.isInfinite() || worst > 1.6 * median) return Taught.Again(scores.indexOf(worst))
        return Taught.Done(Model(templates, worst))
    }

    /** Listens to the frames as they come and tells when the last ones were the word. */
    class Detector(model: Model, sensitivity: Int = 1) {
        private val templates = model.templates
        private val threshold = model.threshold * SENSITIVITY[sensitivity.coerceIn(SENSITIVITY.indices)]
        private val keep = 2 * templates.maxOf { it.size }
        private val frames = ArrayDeque<Frame>()
        private var count = 0

        /** The last score over the threshold: below 1 is the word. */
        var last = Double.POSITIVE_INFINITY
            private set

        fun feed(new: List<Frame>): Boolean {
            var heard = false
            for (frame in new) {
                frames.addLast(frame)
                if (frames.size > keep) frames.removeFirst()
                // A look every 40 ms is plenty: the path is free to end on any frame of the window.
                if (++count % 4 != 0 || frames.none { it.rel > SPEECH_DB }) continue
                last = score(templates, frames.toList()) / threshold
                if (last < 1) {
                    heard = true
                    frames.clear()
                }
            }
            return heard
        }
    }

    /**
     * Reads the microphone on a thread of its own and hands [onFrames] the frames of what it hears,
     * on that thread, until [Microphone.close]. Needs RECORD_AUDIO; throws when it cannot open it.
     */
    class Microphone(onFrames: (List<Frame>) -> Unit) : AutoCloseable {
        @SuppressLint("MissingPermission") // The callers ask for it first.
        private val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE),
        )

        @Volatile private var open = true

        private val thread = Thread {
            val features = Features()
            val buffer = ShortArray(1600)
            while (open) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                onFrames(features.push(DoubleArray(read) { buffer[it] / 32768.0 }))
            }
        }

        init {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone" }
            record.startRecording()
            thread.start()
        }

        @Synchronized
        override fun close() {
            if (record.state == AudioRecord.STATE_UNINITIALIZED) return
            open = false
            runCatching { record.stop() }
            thread.join(500)
            record.release()
        }
    }
}
