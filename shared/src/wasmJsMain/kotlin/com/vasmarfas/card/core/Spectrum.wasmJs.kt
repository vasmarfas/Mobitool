package com.vasmarfas.card.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

private fun jsSpectrumStart(fftSize: Int): Unit = js(
    """{
        var a = globalThis.__spectrum || (globalThis.__spectrum = {});
        a.error = '';
        var token = a.token = (a.token || 0) + 1;
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (!a.ctx) a.ctx = new Ctx();
        if (a.ctx.state === 'suspended') a.ctx.resume();
        navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
            .then(function (stream) {
                if (a.token !== token) { stream.getTracks().forEach(function (t) { t.stop(); }); return; }
                if (a.stream) a.stream.getTracks().forEach(function (t) { t.stop(); });
                a.stream = stream;
                a.node = a.ctx.createMediaStreamSource(stream);
                a.analyser = a.ctx.createAnalyser();
                a.analyser.fftSize = fftSize;
                a.analyser.smoothingTimeConstant = 0;
                a.data = new Float32Array(a.analyser.frequencyBinCount);
                a.node.connect(a.analyser);
            })
            .catch(function (e) { if (a.token === token) a.error = String(e && e.name ? e.name : e); });
    }"""
)

private fun jsSpectrumReady(): Boolean = js("!!(globalThis.__spectrum && globalThis.__spectrum.analyser)")

private fun jsSpectrumError(): String = js("(globalThis.__spectrum && globalThis.__spectrum.error) || ''")

private fun jsSpectrumRate(): Int = js("Math.round(globalThis.__spectrum.ctx.sampleRate)")

private fun jsSpectrumBins(): Int = js("globalThis.__spectrum.analyser.frequencyBinCount")

// silent bins come back as -Infinity, which the peak interpolation cannot take
private fun jsSpectrumSample(): Unit = js(
    """{
        var a = globalThis.__spectrum;
        a.analyser.getFloatFrequencyData(a.data);
        for (var i = 0; i < a.data.length; i++) if (!(a.data[i] > -160)) a.data[i] = -160;
    }"""
)

private fun jsSpectrumAt(index: Int): Double = js("globalThis.__spectrum.data[index]")

private fun jsSpectrumStop(): Unit = js(
    """{
        var a = globalThis.__spectrum;
        if (!a) return;
        a.token = (a.token || 0) + 1;
        if (a.stream) a.stream.getTracks().forEach(function (t) { t.stop(); });
        if (a.node) try { a.node.disconnect(); } catch (e) {}
        a.stream = null; a.node = null; a.analyser = null; a.data = null;
    }"""
)

// AnalyserNode already produces dB magnitudes, so the browser keeps the FFT
actual fun microphoneSpectrumFlow(fftSize: Int): Flow<SpectrumFrame> = flow {
    jsSpectrumStart(fftSize)
    try {
        while (true) {
            delay(60.milliseconds)
            val error = jsSpectrumError()
            if (error.isNotEmpty()) throw IllegalStateException(error)
            if (!jsSpectrumReady()) continue
            jsSpectrumSample()
            val bins = jsSpectrumBins()
            val magnitudes = FloatArray(bins) { jsSpectrumAt(it).toFloat() }
            emit(SpectrumFrame(magnitudes, jsSpectrumRate()))
        }
    } finally {
        jsSpectrumStop()
    }
}
