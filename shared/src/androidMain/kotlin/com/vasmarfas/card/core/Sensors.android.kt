package com.vasmarfas.card.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import com.vasmarfas.card.resources.*
import java.lang.ref.WeakReference
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

object ActivityHolder {
    private var ref: WeakReference<Activity>? = null
    var activity: Activity?
        get() = ref?.get()
        set(value) {
            ref = value?.let { WeakReference(it) }
        }
}

private fun sensorManager() = AppContextHolder.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

private fun androidType(type: SensorType): Int = when (type) {
    SensorType.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
    SensorType.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
    SensorType.MAGNETOMETER -> Sensor.TYPE_MAGNETIC_FIELD
    SensorType.LIGHT -> Sensor.TYPE_LIGHT
    SensorType.PRESSURE -> Sensor.TYPE_PRESSURE
    SensorType.PROXIMITY -> Sensor.TYPE_PROXIMITY
    SensorType.ORIENTATION -> Sensor.TYPE_ROTATION_VECTOR
    SensorType.STEP_COUNTER -> Sensor.TYPE_STEP_COUNTER
    SensorType.HUMIDITY -> Sensor.TYPE_RELATIVE_HUMIDITY
    SensorType.TEMPERATURE -> Sensor.TYPE_AMBIENT_TEMPERATURE
    SensorType.GRAVITY -> Sensor.TYPE_GRAVITY
}

actual fun availableSensors(): Set<SensorType> {
    val manager = sensorManager()
    return SensorType.entries.filter { manager.getDefaultSensor(androidType(it)) != null }.toSet()
}

actual fun sensorFlow(type: SensorType): Flow<SensorReading> = callbackFlow {
    val manager = sensorManager()
    val sensor = manager.getDefaultSensor(androidType(type))
    if (sensor == null) {
        close()
        return@callbackFlow
    }
    val rotation = FloatArray(9)
    val orientation = FloatArray(3)
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = if (type == SensorType.ORIENTATION) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                floatArrayOf(
                    ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat(),
                    Math.toDegrees(orientation[1].toDouble()).toFloat(),
                    Math.toDegrees(orientation[2].toDouble()).toFloat(),
                )
            } else event.values.copyOf()
            trySend(SensorReading(values, System.currentTimeMillis()))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    awaitClose { manager.unregisterListener(listener) }
}

actual suspend fun requestMotionAccess(): Boolean = true

actual fun locationSupported(): Boolean = true

@RequiresPermission(
    anyOf = [
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ],
)
actual fun locationFlow(): Flow<LocationFix> = callbackFlow {
    val manager = AppContextHolder.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            trySend(
                LocationFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else null,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                    bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
                    timestampMs = location.time,
                    provider = location.provider ?: "",
                ),
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) {
        close(IllegalStateException(getString(Res.string.location_turned_off)))
        return@callbackFlow
    }
    try {
        providers.forEach { provider ->
            manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
            manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        }
    } catch (e: SecurityException) {
        close(e)
    }
    awaitClose { manager.removeUpdates(listener) }
}

actual suspend fun batteryInfo(): BatteryInfo? {
    val context = AppContextHolder.context
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
    val percent = if (level >= 0) level * 100 / scale else null
    val statusText = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }
    val details = buildList {
        add("Status" to statusText)
        add("Plugged" to when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "no"
            else -> plugged.toString()
        })
        add("Health" to when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        })
        add("Temperature" to "${intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} °C")
        add("Voltage" to "${intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} ${getString(Res.string.unit_mv)}")
        intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { add("Technology" to it) }
        val current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Int.MIN_VALUE) add("Current now" to "${current / 1000} ${getString(Res.string.unit_ma)}")
        val counter = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (counter != Int.MIN_VALUE && counter > 0) add("Charge counter" to "${counter / 1000} ${getString(Res.string.unit_mah)}")
        val energy = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        if (energy != Long.MIN_VALUE && energy > 0) add("Energy" to "${energy / 1_000_000} ${getString(Res.string.unit_mwh)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cycles = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (cycles >= 0) add("Cycle count" to cycles.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val remaining = manager.computeChargeTimeRemaining()
            if (remaining > 0) add("Time to full" to formatDurationMs(remaining))
        }
    }
    return BatteryInfo(percent, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, details)
}

private fun cameraManager() = AppContextHolder.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

private fun torchCameraId(): String? = runCatching {
    val manager = cameraManager()
    manager.cameraIdList.firstOrNull { id ->
        val c = manager.getCameraCharacteristics(id)
        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
}.getOrNull()

actual fun torchSupported(): Boolean = torchCameraId() != null

actual fun setTorch(on: Boolean): Boolean = runCatching {
    val id = torchCameraId() ?: return false
    cameraManager().setTorchMode(id, on)
    true
}.getOrDefault(false)

actual fun displayExtras(): List<Pair<String, String>> {
    val context = AppContextHolder.context
    val metrics: DisplayMetrics = context.resources.displayMetrics
    // the display belongs to the visual context: asked through the application one, getDisplay
    // throws from API 30 on
    val activity = ActivityHolder.activity
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity?.display
    } else {
        @Suppress("DEPRECATION") activity?.windowManager?.defaultDisplay
    }
    val widthIn = metrics.widthPixels / metrics.xdpi
    val heightIn = metrics.heightPixels / metrics.ydpi
    val diagonal = sqrt((widthIn * widthIn + heightIn * heightIn).toDouble())
    return buildList {
        add("Resolution" to "${metrics.widthPixels} × ${metrics.heightPixels} px")
        add("Density" to "${metrics.density}x (${metrics.densityDpi} dpi)")
        add("Physical DPI" to "${metrics.xdpi.fmt1()} × ${metrics.ydpi.fmt1()}")
        add("Diagonal" to "${diagonal.fmt(2)}\"")
        display?.let { d ->
            add("Refresh rate" to "${d.refreshRate.fmt1()} Hz")
            add("Supported modes" to d.supportedModes.joinToString(", ") { m -> "${m.physicalWidth}×${m.physicalHeight}@${m.refreshRate.fmt1()}" })
            add("HDR" to d.isHdr.toString())
        }
        add("Font scale" to context.resources.configuration.fontScale.toString())
    }
}

private fun Float.fmt1(): String = toDouble().fmt(1)

actual fun screenDpi(): Float? = AppContextHolder.context.resources.displayMetrics.xdpi

actual fun screenPixels(): Pair<Int, Int>? = AppContextHolder.context.resources.displayMetrics.let {
    it.widthPixels to it.heightPixels
}

actual fun appleScreen(): AppleScreen? = null

actual suspend fun displayPanels(): List<DisplayPanel> = emptyList()

actual fun microphoneSupported(): Boolean = true

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
actual fun microphoneLevelFlow(): Flow<Double> = flow {
    val sampleRate = 44100
    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val buffer = ShortArray(maxOf(minBuffer, 4096))
    val record = try {
        AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer.size * 2)
    } catch (e: SecurityException) {
        throw IllegalStateException("NotAllowedError")
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IllegalStateException("NotFoundError")
    }
    try {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("NotReadableError")
        while (currentCoroutineContextActive()) {
            val read = record.read(buffer, 0, buffer.size)
            if (read < 0) throw IllegalStateException("NotReadableError")
            if (read > 0) {
                var sum = 0.0
                for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i].toDouble()
                val rms = sqrt(sum / read)
                emit(20 * log10((rms / 32768.0).coerceAtLeast(1e-9)) + 90)
            }
            delay(50)
        }
    } finally {
        record.stop()
        record.release()
    }
}.flowOn(Dispatchers.IO)

private suspend fun currentCoroutineContextActive(): Boolean = withContext(Dispatchers.Default) { isActive }

actual fun setScreenBrightness(value: Float?) {
    val activity = ActivityHolder.activity ?: return
    activity.runOnUiThread {
        val params = activity.window.attributes
        params.screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
    }
}
