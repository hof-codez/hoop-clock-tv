package com.hof.hoopclock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device Kokoro voice (am_michael), fully offline.
 * The voice files ship inside the app and are copied to private storage on first launch.
 */
object KokoroEngine {
    private const val DIR = "kokoro"
    private const val MARK = "copied.v1"
    const val SID = 16 // am_michael in kokoro-multi-lang-v1_0

    @Volatile var state = "off"
    @Volatile var message = ""
    @Volatile var lastRtf = 0f

    private var tts: OfflineTts? = null
    private val lock = Any()
    private val session = AtomicInteger(0)
    @Volatile private var track: AudioTrack? = null

    fun loadAsync(ctx: Context) {
        if (state == "loading" || state == "ready") return
        state = "loading"; message = "preparing voice files"
        Thread {
            try {
                val am = ctx.assets
                val listed = am.list(DIR)
                if (listed == null || listed.isEmpty()) {
                    state = "missing"; message = "voice files are not in this build"; return@Thread
                }
                val base = File(ctx.filesDir, DIR)
                if (!File(base, MARK).exists()) {
                    message = "copying voice files (first launch only)"
                    copyAssetDir(ctx, DIR, base)
                    File(base, MARK).writeText("ok")
                }
                message = "loading the voice model"
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = File(base, "model.int8.onnx").absolutePath,
                    voices = File(base, "voices.bin").absolutePath,
                    tokens = File(base, "tokens.txt").absolutePath,
                    dataDir = File(base, "espeak-ng-data").absolutePath,
                    lexicon = File(base, "lexicon-us-en.txt").absolutePath,
                    dictDir = File(base, "dict").absolutePath,
                )
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = 2, debug = false, provider = "cpu"),
                )
                tts = OfflineTts(assetManager = null, config = cfg)
                state = "ready"; message = "ready"
            } catch (t: Throwable) {
                state = "error"; message = (t.message ?: t.toString()).take(200)
            }
        }.start()
    }

    private fun copyAssetDir(ctx: Context, path: String, out: File) {
        val list = ctx.assets.list(path)
        if (list == null || list.isEmpty()) {
            out.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> FileOutputStream(out).use { o -> i.copyTo(o, 1 shl 16) } }
            return
        }
        out.mkdirs()
        for (n in list) copyAssetDir(ctx, "$path/$n", File(out, n))
    }

    /** Speak text sentence by sentence: the next sentence is made while the current one plays. */
    fun speak(text: String, speed: Float): Boolean {
        val t = tts ?: return false
        stop()
        val my = session.incrementAndGet()
        val q = LinkedBlockingQueue<FloatArray>()
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        Thread {
            try {
                for (s in sentences) {
                    if (session.get() != my) break
                    val t0 = System.nanoTime()
                    val a = synchronized(lock) { t.generate(s, SID, speed) }
                    val dt = (System.nanoTime() - t0) / 1e9f
                    if (a.samples.isNotEmpty()) lastRtf = dt / (a.samples.size.toFloat() / a.sampleRate)
                    q.put(a.samples)
                }
            } catch (_: Throwable) {
            } finally {
                q.put(FloatArray(0))
            }
        }.start()
        Thread {
            val sr = t.sampleRate()
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, sr * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = tr
            try {
                tr.play()
                while (session.get() == my) {
                    val chunk = q.take()
                    if (chunk.isEmpty()) break
                    for (i in chunk.indices) chunk[i] = (chunk[i] * 0.9f).coerceIn(-1f, 1f)
                    tr.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
                if (session.get() == my) Thread.sleep(400)
            } catch (_: Throwable) {
            } finally {
                try { tr.stop() } catch (_: Throwable) {}
                try { tr.release() } catch (_: Throwable) {}
                if (track === tr) track = null
            }
        }.start()
        return true
    }

    fun stop() {
        session.incrementAndGet()
        track?.let { try { it.pause(); it.flush(); it.stop() } catch (_: Throwable) {} }
    }

    /** Render one short line (a player name) to a WAV file, returned as base64. */
    fun renderWavBase64(text: String, speed: Float): String? {
        val t = tts ?: return null
        val a = synchronized(lock) { t.generate(text, SID, speed) }
        var s = a.samples
        if (s.isEmpty()) return null
        var i0 = 0; var i1 = s.size - 1
        while (i0 < s.size && Math.abs(s[i0]) < 0.01f) i0++
        while (i1 > i0 && Math.abs(s[i1]) < 0.01f) i1--
        if (i1 <= i0) return null
        s = s.copyOfRange(maxOf(0, i0 - a.sampleRate / 50), minOf(s.size, i1 + a.sampleRate / 20))
        return Base64.encodeToString(wav(s, a.sampleRate), Base64.NO_WRAP)
    }

    private fun wav(s: FloatArray, sr: Int): ByteArray {
        val n = s.size
        val data = ByteArray(44 + n * 2)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (v in s) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return data
    }
}
