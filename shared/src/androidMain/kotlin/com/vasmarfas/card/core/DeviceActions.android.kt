package com.vasmarfas.card.core

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

actual fun playTone(frequencyHz: Double, durationMs: Int, volume: Float) {
    thread(isDaemon = true, name = "tone") {
        runCatching {
            val sampleRate = 44100
            val samples = sampleRate * durationMs / 1000
            val buffer = ShortArray(samples)
            val fade = min(samples / 10, 400)
            for (i in 0 until samples) {
                val envelope = when {
                    i < fade -> i.toFloat() / fade
                    i > samples - fade -> (samples - i).toFloat() / fade
                    else -> 1f
                }
                buffer[i] = (sin(2.0 * PI * frequencyHz * i / sampleRate) * Short.MAX_VALUE * volume * envelope).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buffer, 0, buffer.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 50)
            track.stop()
            track.release()
        }
    }
}

@RequiresPermission(Manifest.permission.VIBRATE)
actual fun vibrate(durationMs: Int) {
    val context = AppContextHolder.context
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
}

actual fun setSystemBarsHidden(hidden: Boolean) {
    val activity = ActivityHolder.activity ?: return
    activity.runOnUiThread {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private val lightNavigationScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val darkNavigationScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

actual fun setSystemBarsDark(dark: Boolean, followSystem: Boolean) {
    val activity = ActivityHolder.activity as? ComponentActivity ?: return
    activity.enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(lightNavigationScrim, darkNavigationScrim) { dark },
    )
}
