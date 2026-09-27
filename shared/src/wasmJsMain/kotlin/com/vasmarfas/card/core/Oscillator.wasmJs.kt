package com.vasmarfas.card.core

// without a sampleRate option the context runs at the output device's own rate
private fun jsToneContextRate(): Int = js(
    """{
    var t = globalThis.__tone || (globalThis.__tone = {});
    if (!t.ctx) t.ctx = new (window.AudioContext || window.webkitAudioContext)();
    return Math.round(t.ctx.sampleRate);
}"""
)

// the waveform crosses as an index, so the Web Audio enum names stay on the JS side
private fun jsStartTone(frequencyHz: Double, waveformIndex: Int, volume: Double): Unit = js(
    """{
    var names = ['sine', 'square', 'triangle', 'sawtooth', 'noise'];
    var waveform = names[waveformIndex];
    var noise = waveform === 'noise';
    var t = globalThis.__tone || (globalThis.__tone = {});
    if (!t.ctx) t.ctx = new (window.AudioContext || window.webkitAudioContext)();
    var ctx = t.ctx;
    if (ctx.state === 'suspended') ctx.resume();
    var now = ctx.currentTime;

    if (t.source && t.isNoise !== noise) {
        try { t.source.stop(); } catch (e) {}
        try { t.source.disconnect(); } catch (e) {}
        t.source = null;
    }
    if (!t.gain) {
        t.gain = ctx.createGain();
        t.gain.gain.value = 0;
        t.gain.connect(ctx.destination);
    }
    if (!t.source) {
        if (noise) {
            var frames = Math.round(ctx.sampleRate);
            var buffer = ctx.createBuffer(1, frames, ctx.sampleRate);
            var data = buffer.getChannelData(0);
            for (var i = 0; i < frames; i++) data[i] = Math.random() * 2 - 1;
            var src = ctx.createBufferSource();
            src.buffer = buffer;
            src.loop = true;
            src.connect(t.gain);
            src.start();
            t.source = src;
        } else {
            var osc = ctx.createOscillator();
            osc.type = waveform;
            osc.frequency.value = frequencyHz;
            osc.connect(t.gain);
            osc.start();
            t.source = osc;
        }
        t.isNoise = noise;
    } else if (!noise) {
        t.source.type = waveform;
        t.source.frequency.setTargetAtTime(frequencyHz, now, 0.01);
    }
    t.gain.gain.setTargetAtTime(volume, now, 0.02);
}"""
)

private fun jsStopTone(): Unit = js(
    """{
    var t = globalThis.__tone;
    if (t && t.ctx) {
        if (t.gain) t.gain.gain.setTargetAtTime(0, t.ctx.currentTime, 0.02);
        if (t.source) {
            var source = t.source;
            t.source = null;
            setTimeout(function () {
                try { source.stop(); } catch (e) {}
                try { source.disconnect(); } catch (e) {}
            }, 120);
        }
    }
}"""
)

actual fun toneSampleRate(): Int = runCatching { jsToneContextRate() }.getOrDefault(48_000)

actual fun startTone(frequencyHz: Double, waveform: Waveform, volume: Float) {
    jsStartTone(frequencyHz, waveform.ordinal, volume.toDouble())
}

actual fun stopTone() {
    jsStopTone()
}
