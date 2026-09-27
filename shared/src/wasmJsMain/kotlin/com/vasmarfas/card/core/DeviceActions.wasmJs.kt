package com.vasmarfas.card.core

private fun jsPlayTone(frequency: Double, durationMs: Int, volume: Float): Unit = js(
    """{
        try {
            var ctx = window.__toneCtx || (window.__toneCtx = new (window.AudioContext || window.webkitAudioContext)());
            if (ctx.state === 'suspended') { ctx.resume(); }
            var osc = ctx.createOscillator();
            var gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.value = frequency;
            var now = ctx.currentTime;
            var end = now + durationMs / 1000;
            gain.gain.setValueAtTime(0.0001, now);
            gain.gain.exponentialRampToValueAtTime(volume, now + 0.01);
            gain.gain.setValueAtTime(volume, Math.max(now + 0.01, end - 0.02));
            gain.gain.exponentialRampToValueAtTime(0.0001, end);
            osc.connect(gain); gain.connect(ctx.destination);
            osc.start(now); osc.stop(end + 0.01);
        } catch (e) {}
    }"""
)

private fun jsVibrate(durationMs: Int): Unit = js("{ try { if (navigator.vibrate) navigator.vibrate(durationMs); } catch (e) {} }")

private fun jsTimeZones(): String = js(
    """(function(){ try { return (Intl.supportedValuesOf ? Intl.supportedValuesOf('timeZone') : []).join('\n'); } catch (e) { return ''; } })()"""
)

private fun jsZoneOffsetMinutes(zone: String, epochSeconds: Double): Double = js(
    """(function(){
        try {
            var d = new Date(epochSeconds * 1000);
            var parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).formatToParts(d);
            var o = {};
            for (var i = 0; i < parts.length; i++) { o[parts[i].type] = parts[i].value; }
            var asUtc = Date.UTC(parseInt(o.year), parseInt(o.month) - 1, parseInt(o.day), parseInt(o.hour) % 24, parseInt(o.minute), parseInt(o.second));
            return Math.round((asUtc - d.getTime()) / 60000);
        } catch (e) { return NaN; }
    })()"""
)

private fun jsSystemZone(): String = js("(function(){ try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (e) { return 'UTC'; } })()")

actual fun playTone(frequencyHz: Double, durationMs: Int, volume: Float) = jsPlayTone(frequencyHz, durationMs, volume)

actual fun vibrate(durationMs: Int) = jsVibrate(durationMs)

actual fun timeZoneIds(): List<String> = jsTimeZones().split('\n').filter { it.isNotBlank() }

actual fun timeZoneOffsetSeconds(zoneId: String, epochSeconds: Long): Int? {
    val minutes = jsZoneOffsetMinutes(zoneId, epochSeconds.toDouble())
    return if (minutes.isNaN()) null else (minutes * 60).toInt()
}

actual fun systemTimeZoneId(): String = jsSystemZone()

actual fun setSystemBarsHidden(hidden: Boolean) {
    runCatching { if (hidden) requestBrowserFullscreen() else exitBrowserFullscreen() }
}

actual fun setSystemBarsDark(dark: Boolean, followSystem: Boolean) = Unit

private fun requestBrowserFullscreen(): Unit = js("{ var e = document.documentElement; if (e.requestFullscreen) e.requestFullscreen(); }")

private fun exitBrowserFullscreen(): Unit = js("{ if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen(); }")
