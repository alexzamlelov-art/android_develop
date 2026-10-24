package com.example.guitartuner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity() {
    private val sampleRate = 44100
    @Volatile private var listening = false
    private var recorder: AudioRecord? = null
    private lateinit var noteView: TextView
    private lateinit var frequencyView: TextView
    private lateinit var statusView: TextView
    private lateinit var centsView: TextView
    private lateinit var needle: TuningMeter
    private lateinit var startButton: Button
    private val notes = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val strings = listOf(
        GuitarString("E", 82.41), GuitarString("A", 110.00), GuitarString("D", 146.83),
        GuitarString("G", 196.00), GuitarString("B", 246.94), GuitarString("E", 329.63)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 12, 11)
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(18))
            setBackgroundColor(Color.rgb(8, 12, 11))
        }
        val title = label("GUITAR TUNER", 18, Color.WHITE, true)
        root.addView(title, matchWrap())
        root.addView(label("STANDARD TUNING · E A D G B E", 11, Color.rgb(142, 160, 149), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(26))
        }, matchWrap())

        val stringRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        strings.forEachIndexed { i, s ->
            val b = TextView(this).apply {
                text = s.name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(if (i == 0) Color.rgb(42, 83, 59) else Color.rgb(29, 36, 32), 100)
                setOnClickListener { showStringHint(s) }
            }
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
            stringRow.addView(b, lp)
        }
        root.addView(stringRow, matchWrap())

        noteView = label("—", 76, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(32), 0, 0) }
        root.addView(noteView, matchWrap())
        frequencyView = label("Play a single string", 17, Color.rgb(170, 185, 176), false).apply { gravity = Gravity.CENTER }
        root.addView(frequencyView, matchWrap())
        needle = TuningMeter(this)
        root.addView(needle, LinearLayout.LayoutParams(-1, dp(100)).apply { topMargin = dp(22) })
        centsView = label("0 cents", 15, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(12)) }
        root.addView(centsView, matchWrap())
        statusView = label("Ready to tune", 16, Color.rgb(116, 218, 143), true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(19, 34, 25), 18)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        root.addView(statusView, matchWrap())
        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        startButton = Button(this).apply {
            text = "START TUNING"
            textSize = 15f
            setTextColor(Color.BLACK)
            background = rounded(Color.rgb(158, 230, 55), 18)
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(label("Bring the phone close to the guitar. Tune in a quiet room.", 11, Color.rgb(120, 132, 125), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }, matchWrap())
        setContentView(root)
    }

    private fun showStringHint(s: GuitarString) {
        frequencyView.text = "Target ${s.name} · ${"%.2f".format(s.hz)} Hz"
        statusView.text = "Pluck this string to tune"
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { statusView.text = "Microphone is unavailable"; return }
        try {
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release(); recorder = null
                statusView.text = "Could not start microphone"; return
            }
            recorder?.startRecording()
            listening = true
            startButton.text = "STOP"
            statusView.text = "Listening… play one string"
            Thread { audioLoop() }.start()
        } catch (_: Exception) {
            statusView.text = "Microphone could not be started"
            stopListening()
        }
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(8192)
        while (listening) {
            val count = try { recorder?.read(buffer, 0, buffer.size) ?: 0 } catch (_: Exception) { 0 }
            if (count > 2048) {
                val samples = DoubleArray(count) { buffer[it].toDouble() / 32768.0 }
                val rms = sqrt(samples.sumOf { it * it } / count)
                val hz = if (rms > 0.008) detectPitch(samples, sampleRate) else -1.0
                if (hz in 60.0..1000.0) runOnUiThread { updatePitch(hz) }
            }
        }
    }

    private fun detectPitch(x: DoubleArray, sr: Int): Double {
        val n = x.size
        val minLag = sr / 1000
        val maxLag = minOf(sr / 60, n / 2)
        var bestLag = -1
        var best = 0.0
        // Normalized autocorrelation: sufficient for a simple first version.
        for (lag in minLag..maxLag) {
            var cross = 0.0; var a = 0.0; var b = 0.0
            val limit = n - lag
            var i = 0
            while (i < limit) {
                val v = x[i]; val w = x[i + lag]
                cross += v * w; a += v * v; b += w * w
                i += 2
            }
            val corr = if (a > 0.0 && b > 0.0) cross / sqrt(a * b) else 0.0
            if (corr > best) { best = corr; bestLag = lag }
        }
        if (bestLag < 0 || best < 0.55) return -1.0
        // Parabolic interpolation improves frequency resolution between sample lags.
        fun correlation(lag: Int): Double {
            var sum = 0.0; var a = 0.0; var b = 0.0
            var i = 0
            while (i < n - lag) { val p=x[i]; val q=x[i+lag]; sum+=p*q; a+=p*p; b+=q*q; i+=2 }
            return if (a > 0 && b > 0) sum / sqrt(a*b) else 0.0
        }
        val left = correlation(maxOf(minLag, bestLag - 1))
        val center = correlation(bestLag)
        val right = correlation(minOf(maxLag, bestLag + 1))
        val denom = left - 2 * center + right
        val shift = if (abs(denom) > 1e-9) 0.5 * (left - right) / denom else 0.0
        return sr / (bestLag + shift)
    }

    private fun updatePitch(hz: Double) {
        val midi = (69 + 12 * log2(hz / 440.0)).roundToInt()
        val note = notes[(midi % 12 + 12) % 12]
        val octave = midi / 12 - 1
        val targetHz = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
        val cents = (1200 * log2(hz / targetHz)).roundToInt().coerceIn(-50, 50)
        noteView.text = note
        frequencyView.text = "${"%.1f".format(hz)} Hz  ·  ${note}${octave}"
        centsView.text = if (cents > 0) "+$cents cents" else "$cents cents"
        needle.cents = cents.toFloat()
        needle.invalidate()
        statusView.text = when {
            abs(cents) <= 4 -> "IN TUNE ✓"
            cents < 0 -> "TOO LOW · tighten the string"
            else -> "TOO HIGH · loosen the string"
        }
        statusView.setTextColor(if (abs(cents) <= 4) Color.rgb(158, 230, 55) else Color.rgb(255, 190, 90))
    }

    private fun stopListening() {
        listening = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        if (::startButton.isInitialized) startButton.text = "START TUNING"
        if (::statusView.isInitialized) statusView.text = "Stopped"
    }

    override fun onDestroy() { stopListening(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) statusView.text = "Microphone allowed · tap Start"
        else if (requestCode == 10) statusView.text = "Microphone permission is required"
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    data class GuitarString(val name: String, val hz: Double)

    private class TuningMeter(context: android.content.Context) : View(context) {
        var cents = 0f
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val cy = height * 0.56f
            paint.strokeWidth = 3f
            paint.color = Color.rgb(56, 70, 61)
            canvas.drawLine(w * .08f, cy, w * .92f, cy, paint)
            for (i in -5..5) {
                val x = w * (.5f + i * .075f)
                paint.color = if (i == 0) Color.rgb(158, 230, 55) else Color.rgb(85, 103, 91)
                paint.strokeWidth = if (i == 0) 5f else 2f
                canvas.drawLine(x, cy - if (i == 0) 25f else 14f, x, cy + if (i == 0) 25f else 14f, paint)
            }
            val x = (w * (.5f + cents.coerceIn(-50f, 50f) / 50f * .375f)).coerceIn(w*.08f, w*.92f)
            paint.color = if (abs(cents) <= 4) Color.rgb(158, 230, 55) else Color.WHITE
            paint.strokeWidth = 5f
            canvas.drawLine(x, cy - 34f, x, cy + 34f, paint)
            paint.textSize = 12f; paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.color = Color.rgb(135, 150, 140)
            canvas.drawText("FLAT", w*.12f, height-7f, paint)
            canvas.drawText("IN TUNE", w*.5f, height-7f, paint)
            canvas.drawText("SHARP", w*.88f, height-7f, paint)
        }
    }
}
