package com.vasmarfas.card.core

import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt

private fun jsSensorsInit(): Unit = js(
    """{
        if (window.__sensors) return;
        var s = { hasOrientation: false, hasMotion: false, alpha: NaN, beta: NaN, gamma: NaN, absolute: false, heading: NaN,
                  ax: NaN, ay: NaN, az: NaN, gx: NaN, gy: NaN, gz: NaN, rx: NaN, ry: NaN, rz: NaN, t: 0 };
        window.__sensors = s;
        // Chromium fires both events, and alpha of the relative one points somewhere other than north
        var onOrientation = function (e) {
            if (s.absolute && !e.absolute) return;
            s.hasOrientation = true;
            s.alpha = e.alpha; s.beta = e.beta; s.gamma = e.gamma; s.absolute = !!e.absolute;
            if (typeof e.webkitCompassHeading === 'number') s.heading = e.webkitCompassHeading;
            s.t = Date.now();
        };
        if ('ondeviceorientationabsolute' in window) window.addEventListener('deviceorientationabsolute', onOrientation);
        window.addEventListener('deviceorientation', onOrientation);
        window.addEventListener('devicemotion', function (e) {
            s.hasMotion = true;
            var a = e.accelerationIncludingGravity || {}; var g = e.acceleration || {}; var r = e.rotationRate || {};
            s.ax = a.x; s.ay = a.y; s.az = a.z; s.gx = g.x; s.gy = g.y; s.gz = g.z; s.rx = r.beta; s.ry = r.gamma; s.rz = r.alpha;
            s.t = Date.now();
        });
    }"""
)

private fun jsSensorValue(key: String): Double = js("(function(){ var s = window.__sensors; if (!s) return NaN; var v = s[key]; return (typeof v === 'number') ? v : NaN; })()")

private fun jsIsMobile(): Boolean = js("/Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent)")

private fun jsAppleTouch(): Boolean = js("/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)")

private fun jsHasGenericSensor(name: String): Boolean = js("(typeof window[name] === 'function')")

private fun jsGenericSensorStart(name: String): Unit = js(
    """{
        window.__gs = window.__gs || {};
        if (window.__gs[name] && window.__gs[name].sensor) return;
        var slot = { x: NaN, y: NaN, z: NaN, error: '' };
        window.__gs[name] = slot;
        try {
            var Ctor = window[name];
            if (typeof Ctor !== 'function') { slot.error = 'unsupported'; return; }
            var sensor = new Ctor({ frequency: 10 });
            slot.sensor = sensor;
            sensor.addEventListener('reading', function () {
                if (name === 'AmbientLightSensor') { slot.x = sensor.illuminance; slot.y = 0; slot.z = 0; }
                else { slot.x = sensor.x; slot.y = sensor.y; slot.z = sensor.z; }
            });
            sensor.addEventListener('error', function (e) { slot.error = (e && e.error && e.error.name) || 'error'; });
            sensor.start();
        } catch (e) { slot.error = (e && e.message) || 'error'; }
    }"""
)

private fun jsGenericSensorStop(name: String): Unit = js(
    """{
        var slot = window.__gs && window.__gs[name];
        if (slot && slot.sensor) { try { slot.sensor.stop(); } catch (e) {} slot.sensor = null; }
    }"""
)

private fun jsGenericSensorValue(name: String, axis: String): Double = js(
    """(function(){ var slot = window.__gs && window.__gs[name]; if (!slot) return NaN; var v = slot[axis]; return (typeof v === 'number') ? v : NaN; })()"""
)

private fun jsGenericSensorError(name: String): String = js("(function(){ var slot = window.__gs && window.__gs[name]; return slot ? (slot.error || '') : ''; })()")

private fun jsRequestMotionPermission(): Unit = js(
    """{
        window.__motionPermission = 'pending';
        try {
            if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
                DeviceMotionEvent.requestPermission().then(function (r) { window.__motionPermission = r; }).catch(function () { window.__motionPermission = 'denied'; });
                if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
                    DeviceOrientationEvent.requestPermission().catch(function () {});
                }
            } else {
                window.__motionPermission = 'granted';
            }
        } catch (e) { window.__motionPermission = 'granted'; }
    }"""
)

private fun jsMotionPermission(): String = js("(window.__motionPermission || 'granted')")

private fun jsGeoStart(): Unit = js(
    """{
        if (window.__geoWatch !== undefined) return;
        window.__geo = { lat: NaN, lon: NaN, alt: NaN, acc: NaN, speed: NaN, heading: NaN, t: 0, error: '' };
        if (!navigator.geolocation) { window.__geo.error = 'unsupported'; window.__geoWatch = -1; return; }
        window.__geoWatch = navigator.geolocation.watchPosition(function (p) {
            var g = window.__geo; var c = p.coords;
            g.lat = c.latitude; g.lon = c.longitude; g.alt = (c.altitude === null ? NaN : c.altitude); g.acc = c.accuracy;
            g.speed = (c.speed === null ? NaN : c.speed); g.heading = (c.heading === null ? NaN : c.heading); g.t = p.timestamp; g.error = '';
        }, function (err) { window.__geo.error = err.message || ('error ' + err.code); }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 });
    }"""
)

private fun jsGeoStop(): Unit = js("{ if (window.__geoWatch !== undefined && window.__geoWatch >= 0 && navigator.geolocation) { navigator.geolocation.clearWatch(window.__geoWatch); } window.__geoWatch = undefined; }")

private fun jsGeoValue(key: String): Double = js("(function(){ var g = window.__geo; if (!g) return NaN; var v = g[key]; return (typeof v === 'number') ? v : NaN; })()")

private fun jsGeoError(): String = js("(function(){ var g = window.__geo; return g ? (g.error || '') : ''; })()")

private fun jsHasGeolocation(): Boolean = js("!!navigator.geolocation")

private fun jsBatteryRequest(): Unit = js(
    """{
        if (!navigator.getBattery) { window.__battery = { unsupported: true }; return; }
        navigator.getBattery().then(function (b) {
            var update = function () { window.__battery = { level: b.level, charging: b.charging, chargingTime: b.chargingTime, dischargingTime: b.dischargingTime }; };
            update();
            b.addEventListener('levelchange', update); b.addEventListener('chargingchange', update);
        }).catch(function () { window.__battery = { unsupported: true }; });
    }"""
)

private fun jsBatteryValue(key: String): Double = js("(function(){ var b = window.__battery; if (!b) return NaN; var v = b[key]; if (typeof v === 'boolean') return v ? 1 : 0; return (typeof v === 'number') ? v : NaN; })()")

private fun jsBatteryUnsupported(): Boolean = js("!!(window.__battery && window.__battery.unsupported)")

private fun jsScreenInfo(): String = js("(screen.width + 'x' + screen.height + '|' + (window.devicePixelRatio || 1) + '|' + (screen.colorDepth || 0) + '|' + window.innerWidth + 'x' + window.innerHeight + '|' + ((screen.orientation && screen.orientation.type) || ''))")

private fun jsMicStart(): Unit = js(
    """{
        if (window.__mic && window.__mic.active) return;
        var token = window.__micToken = (window.__micToken || 0) + 1;
        window.__mic = { level: NaN, active: false, error: '' };
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { window.__mic.error = 'unsupported'; return; }
        navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } }).then(function (stream) {
            if (window.__micToken !== token) { stream.getTracks().forEach(function (t) { t.stop(); }); return; }
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            var source = ctx.createMediaStreamSource(stream);
            var analyser = ctx.createAnalyser();
            analyser.fftSize = 2048;
            source.connect(analyser);
            var data = new Float32Array(analyser.fftSize);
            var m = window.__mic; m.active = true; m.stream = stream; m.ctx = ctx;
            m.timer = setInterval(function () {
                analyser.getFloatTimeDomainData(data);
                var sum = 0; for (var i = 0; i < data.length; i++) sum += data[i] * data[i];
                var rms = Math.sqrt(sum / data.length);
                m.level = 20 * Math.log10(Math.max(rms, 1e-9)) + 90;
            }, 50);
        }).catch(function (e) { if (window.__micToken === token) window.__mic.error = String(e && e.name ? e.name : e); });
    }"""
)

private fun jsMicStop(): Unit = js(
    """{
        window.__micToken = (window.__micToken || 0) + 1;
        var m = window.__mic; if (!m) return;
        if (m.timer) clearInterval(m.timer);
        if (m.stream) m.stream.getTracks().forEach(function (t) { t.stop(); });
        if (m.ctx) m.ctx.close();
        window.__mic = { level: NaN, active: false, error: '' };
    }"""
)

private fun jsMicLevel(): Double = js("(function(){ var m = window.__mic; return (m && typeof m.level === 'number') ? m.level : NaN; })()")

private fun jsMicError(): String = js("(function(){ var m = window.__mic; return m ? (m.error || '') : ''; })()")

private fun jsHasMediaDevices(): Boolean = js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)")

private fun jsTorchSupported(): Boolean = js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent))")

private fun jsTorch(on: Boolean): Unit = js(
    """{
        window.__torch = window.__torch || {};
        var t = window.__torch;
        var token = t.token = (t.token || 0) + 1;
        if (!on) {
            if (t.track) { try { t.track.applyConstraints({ advanced: [{ torch: false }] }); } catch (e) {} t.track.stop(); t.track = null; }
            return;
        }
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { t.error = 'unsupported'; return; }
        navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } }).then(function (stream) {
            if (t.token !== token) { stream.getTracks().forEach(function (s) { s.stop(); }); return; }
            if (t.track) t.track.stop();
            var track = stream.getVideoTracks()[0];
            t.track = track;
            track.applyConstraints({ advanced: [{ torch: true }] }).catch(function (e) { t.error = 'no torch'; });
        }).catch(function (e) { t.error = (e && e.message) || 'denied'; });
    }"""
)

private fun genericSensorName(type: SensorType): String? = when (type) {
    SensorType.MAGNETOMETER -> "Magnetometer"
    SensorType.LIGHT -> "AmbientLightSensor"
    SensorType.GYROSCOPE -> "Gyroscope"
    SensorType.ACCELEROMETER -> "Accelerometer"
    SensorType.GRAVITY -> "GravitySensor"
    else -> null
}

actual fun availableSensors(): Set<SensorType> = buildSet {
    if (jsIsMobile()) {
        add(SensorType.ACCELEROMETER)
        add(SensorType.GYROSCOPE)
        add(SensorType.ORIENTATION)
    }
    SensorType.entries.forEach { type ->
        genericSensorName(type)?.let { if (jsHasGenericSensor(it)) add(type) }
    }
}

actual fun sensorFlow(type: SensorType): Flow<SensorReading> {
    val generic = genericSensorName(type)
    val motionBacked = type in setOf(SensorType.ACCELEROMETER, SensorType.GYROSCOPE, SensorType.ORIENTATION)
    if (generic == null && !motionBacked) return emptyFlow()
    val useGeneric = generic != null && jsHasGenericSensor(generic) && (type == SensorType.MAGNETOMETER || type == SensorType.LIGHT || !motionBacked)
    return flow {
        if (useGeneric) {
            jsGenericSensorStart(generic)
            try {
                while (true) {
                    delay(100)
                    val error = jsGenericSensorError(generic)
                    if (error.isNotEmpty()) throw IllegalStateException(error)
                    val x = jsGenericSensorValue(generic, "x")
                    if (!x.isNaN()) {
                        emit(
                            SensorReading(
                                floatArrayOf(x.toFloat(), jsGenericSensorValue(generic, "y").toFloat(), jsGenericSensorValue(generic, "z").toFloat()),
                                currentEpochMillis(),
                            ),
                        )
                    }
                }
            } finally {
                jsGenericSensorStop(generic)
            }
        } else {
            jsSensorsInit()
            while (true) {
                delay(50)
                val values = when (type) {
                    SensorType.ACCELEROMETER -> floatArrayOf(jsSensorValue("ax").toFloat(), jsSensorValue("ay").toFloat(), jsSensorValue("az").toFloat())
                    SensorType.GYROSCOPE -> floatArrayOf(jsSensorValue("rx").toFloat(), jsSensorValue("ry").toFloat(), jsSensorValue("rz").toFloat())
                    else -> {
                        val heading = jsSensorValue("heading")
                        val alpha = jsSensorValue("alpha")
                        val azimuth = when {
                            !heading.isNaN() -> heading
                            !alpha.isNaN() -> (360 - alpha) % 360
                            else -> Double.NaN
                        }
                        floatArrayOf(azimuth.toFloat(), jsSensorValue("beta").toFloat(), jsSensorValue("gamma").toFloat())
                    }
                }
                if (values.any { !it.isNaN() }) emit(SensorReading(values, currentEpochMillis()))
            }
        }
    }
}

actual suspend fun requestMotionAccess(): Boolean {
    jsRequestMotionPermission()
    repeat(100) {
        val state = jsMotionPermission()
        if (state != "pending") return state == "granted"
        delay(100)
    }
    return false
}

actual fun locationSupported(): Boolean = jsHasGeolocation()

actual fun locationFlow(): Flow<LocationFix> = flow {
    jsGeoStart()
    try {
        var lastT = 0.0
        while (true) {
            delay(500)
            val error = jsGeoError()
            if (error.isNotEmpty() && jsGeoValue("lat").isNaN()) throw IllegalStateException(error)
            val t = jsGeoValue("t")
            if (t > lastT && !jsGeoValue("lat").isNaN()) {
                lastT = t
                emit(
                    LocationFix(
                        latitude = jsGeoValue("lat"),
                        longitude = jsGeoValue("lon"),
                        altitude = jsGeoValue("alt").takeIf { !it.isNaN() },
                        accuracy = jsGeoValue("acc").takeIf { !it.isNaN() },
                        speed = jsGeoValue("speed").takeIf { !it.isNaN() },
                        bearing = jsGeoValue("heading").takeIf { !it.isNaN() },
                        timestampMs = t.toLong(),
                        provider = "browser",
                    ),
                )
            }
        }
    } finally {
        jsGeoStop()
    }
}

actual suspend fun batteryInfo(): BatteryInfo? {
    jsBatteryRequest()
    repeat(20) {
        if (jsBatteryUnsupported()) return null
        val level = jsBatteryValue("level")
        if (!level.isNaN()) {
            val charging = jsBatteryValue("charging") == 1.0
            val chargingTime = jsBatteryValue("chargingTime")
            val dischargingTime = jsBatteryValue("dischargingTime")
            return BatteryInfo(
                (level * 100).roundToInt(),
                charging,
                buildList {
                    add("Status" to if (charging) "charging" else "discharging")
                    if (!chargingTime.isNaN() && chargingTime.isFinite() && chargingTime > 0) add("Time to full" to formatDurationMs((chargingTime * 1000).toLong()))
                    if (!dischargingTime.isNaN() && dischargingTime.isFinite() && dischargingTime > 0) add("Time to empty" to formatDurationMs((dischargingTime * 1000).toLong()))
                },
            )
        }
        delay(100)
    }
    return null
}

actual fun torchSupported(): Boolean = jsTorchSupported()

actual fun setTorch(on: Boolean): Boolean {
    jsTorch(on)
    return true
}

actual fun displayExtras(): List<Pair<String, String>> {
    val parts = jsScreenInfo().split('|')
    return buildList {
        add("Screen" to (parts.getOrNull(0) ?: ""))
        add("Device pixel ratio" to (parts.getOrNull(1)?.toDoubleOrNull()?.fmt(2) ?: ""))
        add("Color depth" to (parts.getOrNull(2) ?: "") + " bit")
        add("Viewport" to (parts.getOrNull(3) ?: ""))
        parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { add("Orientation" to it) }
    }
}

actual fun screenDpi(): Float? = (96.0 * window.devicePixelRatio).toFloat()

actual fun screenPixels(): Pair<Int, Int>? {
    val ratio = window.devicePixelRatio
    val width = (window.screen.width * ratio).toInt()
    val height = (window.screen.height * ratio).toInt()
    return if (width > 0 && height > 0) width to height else null
}

// Safari names no model, only iPhone or iPad, and iPadOS asks for desktop sites as a Mac with touch
actual fun appleScreen(): AppleScreen? = if (jsAppleTouch()) AppleScreen(null, null) else null

actual suspend fun displayPanels(): List<DisplayPanel> = emptyList()

actual fun microphoneSupported(): Boolean = jsHasMediaDevices()

actual fun microphoneLevelFlow(): Flow<Double> = flow {
    jsMicStart()
    try {
        while (true) {
            delay(50)
            val error = jsMicError()
            if (error.isNotEmpty()) throw IllegalStateException(error)
            val level = jsMicLevel()
            if (!level.isNaN()) emit(level)
        }
    } finally {
        jsMicStop()
    }
}

actual fun setScreenBrightness(value: Float?) = Unit
