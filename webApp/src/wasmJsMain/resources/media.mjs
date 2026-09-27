// loaded on first use through window.vasmarfasImport. Long operations return { promise, progress,
// cancel }, the app polls it
import {
    ALL_FORMATS,
    AudioBufferSink,
    AudioBufferSource,
    BlobSource,
    BufferTarget,
    CanvasSink,
    CanvasSource,
    Conversion,
    FlacOutputFormat,
    Input,
    MkvOutputFormat,
    MovOutputFormat,
    Mp3OutputFormat,
    Mp4OutputFormat,
    OggOutputFormat,
    Output,
    WavOutputFormat,
    WebMOutputFormat,
    canEncodeAudio,
    getFirstEncodableAudioCodec,
    getFirstEncodableVideoCodec,
} from 'mediabunny';

const MIME = {
    mp4: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm', mkv: 'video/x-matroska',
    m4a: 'audio/mp4', mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', flac: 'audio/flac',
};
const VIDEO_FORMATS = new Set(['mp4', 'mov', 'webm', 'mkv']);
const VIDEO_CODECS = { mp4: ['avc', 'hevc', 'vp9', 'av1'], mov: ['avc', 'hevc'], mkv: ['avc', 'hevc', 'vp9', 'av1'], webm: ['vp9', 'vp8', 'av1'] };
const AUDIO_CODECS = {
    mp4: ['aac', 'opus'], mov: ['aac'], mkv: ['aac', 'opus', 'flac'], webm: ['opus', 'vorbis'], m4a: ['aac', 'opus'],
    mp3: ['mp3'], wav: ['pcm-s16'], ogg: ['opus', 'vorbis'], flac: ['flac'],
};
const CODEC_NAMES = { avc: 'h264', 'pcm-s16': 'pcm_s16le' };

function outputFormat(name) {
    switch (name) {
        case 'mp4':
        case 'm4a': return new Mp4OutputFormat({ fastStart: 'in-memory' });
        case 'mov': return new MovOutputFormat({ fastStart: 'in-memory' });
        case 'webm': return new WebMOutputFormat();
        case 'mkv': return new MkvOutputFormat();
        case 'mp3': return new Mp3OutputFormat();
        case 'wav': return new WavOutputFormat();
        case 'ogg': return new OggOutputFormat();
        case 'flac': return new FlacOutputFormat();
    }
    throw new Error('unsupported format ' + name);
}

let mp3Registration = null;

async function audioCodec(name, bitrate) {
    if (name === 'mp3' && !(await canEncodeAudio('mp3'))) {
        mp3Registration ??= import('mediabunny-mp3').then((module) => module.registerMp3Encoder());
        await mp3Registration;
    }
    const codec = await getFirstEncodableAudioCodec(AUDIO_CODECS[name], { bitrate });
    if (!codec) throw new Error('this browser cannot encode audio for .' + name);
    return codec;
}

async function videoCodec(name, width, height, bitrate) {
    const codec = await getFirstEncodableVideoCodec(VIDEO_CODECS[name], { width, height, bitrate });
    if (!codec) throw new Error('this browser cannot encode video for .' + name);
    return codec;
}

function openInput(file) {
    return new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
}

function task(run) {
    const t = { progress: 0, cancelled: false, cancel: null };
    t.promise = run(t);
    return t;
}

function fitInside(width, height, maxSide) {
    const scale = Math.min(1, maxSide / Math.max(width, height));
    return { width: Math.max(2, Math.round(width * scale / 2) * 2), height: Math.max(2, Math.round(height * scale / 2) * 2) };
}

function pixelsOf(canvas) {
    const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    return { width: canvas.width, height: canvas.height, pixels: new Int32Array(data.buffer, data.byteOffset, data.byteLength / 4) };
}

export async function probe(file) {
    const input = openInput(file);
    try {
        const video = await input.getPrimaryVideoTrack();
        const audio = await input.getPrimaryAudioTrack();
        const info = {
            durationMs: Math.round((await input.computeDuration()) * 1000), width: 0, height: 0, frameRate: 0,
            videoCodec: null, audioCodec: null, sampleRate: 0, channels: 0, bitrate: 0,
        };
        if (video) {
            info.width = await video.getDisplayWidth();
            info.height = await video.getDisplayHeight();
            const codec = await video.getCodec();
            info.videoCodec = CODEC_NAMES[codec] ?? codec ?? 'unknown';
            info.frameRate = (await video.computePacketStats(90)).averagePacketRate;
        }
        if (audio) {
            const codec = await audio.getCodec();
            info.audioCodec = CODEC_NAMES[codec] ?? codec ?? 'unknown';
            info.sampleRate = await audio.getSampleRate();
            info.channels = await audio.getNumberOfChannels();
        }
        if (info.durationMs > 0) info.bitrate = Math.round(file.size * 8000 / info.durationMs);
        return info;
    } catch (e) {
        const bitmap = await createImageBitmap(file);
        const info = { durationMs: 0, width: bitmap.width, height: bitmap.height, frameRate: 0, videoCodec: 'image', audioCodec: null, sampleRate: 0, channels: 0, bitrate: 0 };
        bitmap.close();
        return info;
    } finally {
        input.dispose();
    }
}

export async function frame(file, timeMs, maxSide) {
    const input = openInput(file);
    try {
        const track = await input.getPrimaryVideoTrack();
        if (!track) return null;
        const size = fitInside(await track.getDisplayWidth(), await track.getDisplayHeight(), maxSide);
        const sink = new CanvasSink(track, { width: size.width, height: size.height, fit: 'fill' });
        const wrapped = (await sink.getCanvas(timeMs / 1000)) ?? (await sink.getCanvas(await track.getFirstTimestamp()));
        return wrapped ? pixelsOf(wrapped.canvas) : null;
    } finally {
        input.dispose();
    }
}

export async function openFrames(file, fromMs, toMs, fps, maxSide) {
    const input = openInput(file);
    const track = await input.getPrimaryVideoTrack();
    if (!track) {
        input.dispose();
        throw new Error('no video stream');
    }
    const size = fitInside(await track.getDisplayWidth(), await track.getDisplayHeight(), maxSide);
    const sink = new CanvasSink(track, { width: size.width, height: size.height, fit: 'fill', poolSize: 2 });
    const times = [];
    for (let t = fromMs; t < toMs; t += 1000 / fps) times.push(t / 1000);
    const iterator = sink.canvasesAtTimestamps(times);
    return {
        async next() {
            for (;;) {
                const step = await iterator.next();
                if (step.done) {
                    input.dispose();
                    return null;
                }
                if (step.value) return pixelsOf(step.value.canvas);
            }
        },
        close() {
            iterator.return?.();
            input.dispose();
        },
    };
}

export function convert(file, options) {
    return task(async (t) => {
        const input = openInput(file);
        try {
            const format = outputFormat(options.format);
            const output = new Output({ format, target: new BufferTarget() });
            const video = VIDEO_FORMATS.has(options.format)
                ? {
                    width: options.width || undefined,
                    height: options.height || undefined,
                    fit: options.width && options.height ? (options.fill ? 'cover' : 'contain') : undefined,
                    frameRate: options.fps || undefined,
                    codec: options.copy ? undefined : await videoCodec(options.format, options.width || 1280, options.height || 720, options.videoBitrate),
                    bitrate: options.copy ? undefined : options.videoBitrate,
                }
                : { discard: true };
            const audio = options.keepAudio
                ? {
                    codec: await audioCodec(options.format, options.audioBitrate),
                    bitrate: options.audioBitrate,
                    sampleRate: options.sampleRate || undefined,
                    numberOfChannels: options.channels || undefined,
                }
                : { discard: true };
            const trim = options.endMs > 0 ? { start: options.startMs / 1000, end: options.endMs / 1000 } : undefined;
            const conversion = await Conversion.init({ input, output, video, audio, trim, copy: options.copy ? {} : false, showWarnings: false });
            if (!conversion.isValid) {
                throw new Error(conversion.discardedTracks.map((d) => d.reason).join(', ') || 'nothing to convert');
            }
            conversion.onProgress = (p) => { t.progress = p; };
            t.cancel = () => conversion.cancel();
            await conversion.execute();
            return new Blob([output.target.buffer], { type: MIME[options.format] });
        } finally {
            input.dispose();
        }
    });
}

const RATE = 48000;
const WINDOW_FRAMES = 10 * RATE;
const PRE_ROLL = 0.1;
const AHEAD = 2;
const PREVIEW_SIDE = 960;

// decoding starts PRE_ROLL early: the first packet after a seek comes out wrong in AAC and MP3
async function decodeRange(file, startSec, endSec) {
    const input = openInput(file);
    try {
        const track = await input.getPrimaryAudioTrack();
        if (!track) return [];
        const parts = [];
        for await (const wrapped of new AudioBufferSink(track).buffers(Math.max(0, startSec - PRE_ROLL), endSec)) parts.push(wrapped);
        return parts;
    } finally {
        input.dispose();
    }
}

const clipLength = (clip) => (clip.endMs - clip.startMs) / 1000;

function durationOf(project) {
    let end = 0;
    for (const track of project.tracks) for (const clip of track.clips) end = Math.max(end, clip.atMs + clip.endMs - clip.startMs);
    return end;
}

const pictureTracks = (project) => project.tracks.filter((t) => t.kind === 'video' && !t.hidden);

const soundClips = (project) => project.tracks.filter((t) => !t.muted).flatMap((t) => t.clips.filter((c) => c.kind !== 'image' && c.volume > 0));

const activeClip = (track, ms) => track.clips.find((c) => ms >= c.atMs && ms < c.atMs + c.endMs - c.startMs);

// same curve as fadeAt in Media.kt
function fade(clip, t) {
    const fadeIn = clip.fadeInMs / 1000;
    const fadeOut = clip.fadeOutMs / 1000;
    let factor = 1;
    if (fadeIn > 0) factor = Math.min(factor, t / fadeIn);
    if (fadeOut > 0) factor = Math.min(factor, (clipLength(clip) - t) / fadeOut);
    return Math.max(0, Math.min(1, factor));
}

function envelope(param, clip, at, skip) {
    const duration = clipLength(clip);
    const fadeIn = clip.fadeInMs / 1000;
    const fadeOut = clip.fadeOutMs / 1000;
    const corners = [fadeIn, duration - fadeOut, duration];
    if (fadeIn > 0 && fadeOut > 0) corners.push(duration * fadeIn / (fadeIn + fadeOut));
    param.setValueAtTime(clip.volume * fade(clip, skip), at);
    for (const t of corners.filter((c) => c > skip && c <= duration).sort((a, b) => a - b)) {
        param.linearRampToValueAtTime(clip.volume * fade(clip, t), at + t - skip);
    }
}

function place(context, destination, part, sourceStart, sourceEnd, at) {
    let begin = Math.max(part.timestamp, sourceStart);
    const end = Math.min(part.timestamp + part.duration, sourceEnd);
    const late = context.currentTime - (at + begin - sourceStart);
    if (late > 0) begin += late;
    if (end <= begin) return;
    const node = context.createBufferSource();
    node.buffer = part.buffer;
    node.connect(destination);
    node.start(at + begin - sourceStart, begin - part.timestamp, end - begin);
}

async function mixWindow(files, project, from, length) {
    const context = new OfflineAudioContext(2, length, RATE);
    const fromSec = from / RATE;
    const untilSec = (from + length) / RATE;
    for (const clip of soundClips(project)) {
        const clipStart = clip.atMs / 1000;
        if (clipStart >= untilSec || clipStart + clipLength(clip) <= fromSec) continue;
        const skip = Math.max(0, fromSec - clipStart);
        const sourceStart = clip.startMs / 1000 + skip;
        const sourceEnd = Math.min(clip.endMs / 1000, clip.startMs / 1000 + untilSec - clipStart);
        const parts = await decodeRange(files[clip.file], sourceStart, sourceEnd);
        if (!parts.length) continue;
        const gain = context.createGain();
        gain.connect(context.destination);
        const at = Math.max(0, clipStart - fromSec);
        envelope(gain.gain, clip, at, skip);
        for (const part of parts) place(context, gain, part, sourceStart, sourceEnd, at);
    }
    return context.startRendering();
}

function until(context, time, alive) {
    return new Promise((resolve) => {
        const check = () => {
            const wait = time - context.currentTime;
            if (wait <= 0 || !alive()) resolve();
            else setTimeout(check, Math.min(250, wait * 1000));
        };
        check();
    });
}

async function streamSound(context, destination, file, clip, fromSec, startAt, alive) {
    const clipStart = clip.atMs / 1000;
    const skip = Math.max(0, fromSec - clipStart);
    const at = startAt + Math.max(0, clipStart - fromSec);
    await until(context, at - AHEAD, alive);
    if (!alive()) return;
    const input = openInput(file);
    try {
        const track = await input.getPrimaryAudioTrack();
        if (!track || !alive()) return;
        const gain = context.createGain();
        gain.connect(destination);
        envelope(gain.gain, clip, at, skip);
        const sourceStart = clip.startMs / 1000 + skip;
        const sourceEnd = clip.endMs / 1000;
        for await (const part of new AudioBufferSink(track).buffers(Math.max(0, sourceStart - PRE_ROLL), sourceEnd)) {
            if (!alive()) break;
            place(context, gain, part, sourceStart, sourceEnd, at);
            await until(context, at + part.timestamp - sourceStart - AHEAD, alive);
        }
    } finally {
        input.dispose();
    }
}

async function loadImage(file, maxSide) {
    const bitmap = await createImageBitmap(file);
    const size = fitInside(bitmap.width, bitmap.height, maxSide);
    if (size.width >= bitmap.width) return bitmap;
    const smaller = await createImageBitmap(bitmap, { resizeWidth: size.width, resizeHeight: size.height, resizeQuality: 'high' });
    bitmap.close();
    return smaller;
}

class ClipFrames {
    constructor(file, maxSide, fromSec, endMs) {
        this.endMs = endMs;
        this.ready = this.open(file, maxSide, fromSec);
    }

    async open(file, maxSide, fromSec) {
        this.input = openInput(file);
        const track = await this.input.getPrimaryVideoTrack();
        if (!track) return;
        const size = fitInside(await track.getDisplayWidth(), await track.getDisplayHeight(), maxSide);
        this.iterator = new CanvasSink(track, { width: size.width, height: size.height, fit: 'fill', poolSize: 3 }).canvases(fromSec);
        this.current = (await this.iterator.next()).value;
        this.next = (await this.iterator.next()).value;
    }

    async at(sourceSec) {
        await this.ready;
        while (this.next && this.next.timestamp <= sourceSec) {
            this.current = this.next;
            this.next = (await this.iterator.next()).value;
        }
        return this.current?.canvas ?? null;
    }

    close() {
        this.iterator?.return?.();
        this.input.dispose();
    }
}

class Sources {
    constructor(files, maxSide) {
        this.files = files;
        this.maxSide = maxSide;
        this.running = new Map();
        this.seekable = new Map();
        this.images = new Map();
    }

    image(index) {
        let image = this.images.get(index);
        if (!image) {
            image = loadImage(this.files[index], this.maxSide);
            this.images.set(index, image);
        }
        return image;
    }

    frame(layer, clip, ms) {
        if (clip.kind === 'image') return this.image(clip.file);
        const key = layer + ':' + clip.atMs;
        const sourceSec = (clip.startMs + ms - clip.atMs) / 1000;
        let frames = this.running.get(key);
        if (!frames) {
            frames = new ClipFrames(this.files[clip.file], this.maxSide, sourceSec, clip.atMs + clip.endMs - clip.startMs);
            this.running.set(key, frames);
        }
        return frames.at(sourceSec);
    }

    async still(clip, ms) {
        if (clip.kind === 'image') return this.image(clip.file);
        let entry = this.seekable.get(clip.file);
        if (!entry) {
            entry = (async () => {
                const input = openInput(this.files[clip.file]);
                const track = await input.getPrimaryVideoTrack();
                if (!track) return { input };
                const size = fitInside(await track.getDisplayWidth(), await track.getDisplayHeight(), this.maxSide);
                return { input, first: await track.getFirstTimestamp(), sink: new CanvasSink(track, { width: size.width, height: size.height, fit: 'fill' }) };
            })();
            this.seekable.set(clip.file, entry);
        }
        const { sink, first } = await entry;
        const wrapped = sink ? await sink.getCanvas(Math.max(first, (clip.startMs + ms - clip.atMs) / 1000)) : null;
        return wrapped?.canvas ?? null;
    }

    retire(ms) {
        for (const [key, frames] of this.running) {
            if (frames.endMs <= ms) {
                frames.close();
                this.running.delete(key);
            }
        }
    }

    close() {
        for (const frames of this.running.values()) frames.close();
        for (const entry of this.seekable.values()) entry.then((e) => e.input.dispose());
        for (const image of this.images.values()) image.then((b) => b.close());
        this.running.clear();
        this.seekable.clear();
        this.images.clear();
    }
}

// same placement as ClipBox.place and coverCrop in Media.kt
function placement(clip, width, height, frameW, frameH) {
    let sx = 0;
    let sy = 0;
    let sw = width;
    let sh = height;
    let w = frameW * clip.scale;
    let h = frameH * clip.scale;
    if (clip.fill) {
        const frame = frameW / frameH;
        if (width / height > frame) {
            sw = height * frame;
            sx = (width - sw) / 2;
        } else {
            sh = width / frame;
            sy = (height - sh) / 2;
        }
    } else {
        const fit = Math.min(frameW / width, frameH / height);
        w = width * fit * clip.scale;
        h = height * fit * clip.scale;
    }
    return { sx, sy, sw, sh, dx: clip.x * frameW - w / 2, dy: clip.y * frameH - h / 2, dw: w, dh: h };
}

async function gather(project, ms, source) {
    const pictures = await Promise.all(pictureTracks(project).map(async (track, layer) => {
        const clip = activeClip(track, ms);
        return clip ? { clip, image: await source(layer, clip, ms) } : null;
    }));
    return pictures.filter((p) => p?.image);
}

function paint(context, frameW, frameH, pictures, ms) {
    const { width, height } = context.canvas;
    const kx = width / frameW;
    const ky = height / frameH;
    context.globalAlpha = 1;
    context.fillStyle = '#000';
    context.fillRect(0, 0, width, height);
    for (const { clip, image } of pictures) {
        const p = placement(clip, image.width, image.height, frameW, frameH);
        context.globalAlpha = Math.max(0, Math.min(1, clip.opacity * fade(clip, (ms - clip.atMs) / 1000)));
        context.drawImage(image, p.sx, p.sy, p.sw, p.sh, p.dx * kx, p.dy * ky, p.dw * kx, p.dh * ky);
    }
    context.globalAlpha = 1;
}

export function exportTimeline(files, project) {
    return task(async (t) => {
        const spec = project.spec;
        const video = VIDEO_FORMATS.has(spec.format);
        const output = new Output({ format: outputFormat(spec.format), target: new BufferTarget() });
        const totalMs = durationOf(project);
        const canvas = new OffscreenCanvas(spec.width, spec.height);
        const context = canvas.getContext('2d');
        let videoSource = null;
        if (video) {
            const codec = await videoCodec(spec.format, spec.width, spec.height, spec.videoBitrate);
            videoSource = new CanvasSource(canvas, { codec, bitrate: spec.videoBitrate, keyFrameInterval: 2 });
            output.addVideoTrack(videoSource, { frameRate: spec.fps });
        }
        let audioSource = null;
        if (spec.keepAudio) {
            audioSource = new AudioBufferSource({ codec: await audioCodec(spec.format, spec.audioBitrate), bitrate: spec.audioBitrate });
            output.addAudioTrack(audioSource);
        }
        t.cancel = () => { t.cancelled = true; };
        await output.start();
        const totalFrames = Math.max(1, Math.ceil(totalMs / 1000 * RATE));
        let mixed = 0;
        const mixUntil = async (sec) => {
            const target = Math.min(totalFrames, Math.ceil(sec * RATE));
            while (audioSource && mixed < target) {
                if (t.cancelled) throw new Error('cancelled');
                const length = Math.min(WINDOW_FRAMES, totalFrames - mixed);
                await audioSource.add(await mixWindow(files, project, mixed, length));
                mixed += length;
                if (!video) t.progress = Math.min(0.99, mixed / totalFrames);
            }
        };
        try {
            if (video) {
                const sources = new Sources(files, Math.max(spec.width, spec.height));
                try {
                    const count = Math.max(1, Math.ceil(totalMs * spec.fps / 1000));
                    for (let k = 0; k < count; k++) {
                        if (t.cancelled) throw new Error('cancelled');
                        const ms = k * 1000 / spec.fps;
                        sources.retire(ms);
                        paint(context, spec.width, spec.height, await gather(project, ms, (layer, clip, at) => sources.frame(layer, clip, at)), ms);
                        await mixUntil(ms / 1000);
                        await videoSource.add(k / spec.fps, 1 / spec.fps);
                        t.progress = Math.min(0.99, (k + 1) / count);
                    }
                } finally {
                    sources.close();
                }
            }
            await mixUntil(totalMs / 1000);
            await output.finalize();
        } catch (e) {
            await output.cancel();
            throw e;
        }
        t.progress = 1;
        return new Blob([output.target.buffer], { type: MIME[spec.format] });
    });
}

// the AudioContext clock drives the pictures. Paused, every seek paints one still from random access
export function createPreview(canvas) {
    const context = canvas.getContext('2d');
    let files = [];
    let project = null;
    let totalMs = 0;
    let positionMs = 0;
    let playing = false;
    let ended = false;
    let token = 0;
    let audio = null;
    let bus = null;
    let sequence = null;
    let stills = null;
    let drawing = null;
    let pending = false;

    function show(pictures, ms) {
        const ratio = Math.min(2, window.devicePixelRatio || 1);
        const w = Math.max(2, Math.round(canvas.clientWidth * ratio));
        const h = Math.max(2, Math.round(canvas.clientHeight * ratio));
        if (canvas.width !== w) canvas.width = w;
        if (canvas.height !== h) canvas.height = h;
        paint(context, project?.spec.width ?? w, project?.spec.height ?? h, pictures, ms);
    }

    function still() {
        pending = true;
        if (drawing) return;
        drawing = (async () => {
            while (pending && !playing) {
                pending = false;
                if (!project) {
                    show([], 0);
                    continue;
                }
                stills ??= new Sources(files, PREVIEW_SIDE);
                const source = stills;
                const at = Math.min(positionMs, Math.max(0, totalMs - 1));
                const pictures = await gather(project, at, (_, clip, ms) => source.still(clip, ms)).catch(() => []);
                if (!playing) show(pictures, at);
            }
        })().finally(() => {
            drawing = null;
            if (pending && !playing) still();
        });
    }

    function stop() {
        token++;
        playing = false;
        bus?.disconnect();
        bus = null;
        sequence?.close();
        sequence = null;
    }

    const observer = new ResizeObserver(() => { if (!playing) still(); });
    observer.observe(canvas);

    return {
        load(fileList, next) {
            if (fileList.length !== files.length || fileList.some((f, i) => f !== files[i])) {
                stills?.close();
                stills = null;
            }
            files = fileList;
            project = next;
            totalMs = next ? durationOf(next) : 0;
            if (!playing) still();
        },
        seek(ms) {
            positionMs = ms;
            ended = false;
            if (!playing) still();
        },
        async play(ms) {
            stop();
            if (!project) return;
            const mine = token;
            const alive = () => mine === token;
            playing = true;
            ended = false;
            const from = Math.min(ms, totalMs);
            positionMs = from;
            audio ??= new AudioContext();
            await audio.resume();
            if (!alive()) return;
            const clock = audio;
            bus = clock.createGain();
            bus.connect(clock.destination);
            const startAt = clock.currentTime + 0.2;
            for (const clip of soundClips(project)) {
                if (clip.atMs + clip.endMs - clip.startMs > from) {
                    streamSound(clock, bus, files[clip.file], clip, from / 1000, startAt, alive).catch(() => {});
                }
            }
            sequence = new Sources(files, PREVIEW_SIDE);
            const source = sequence;
            const latency = (clock.baseLatency || 0) + (clock.outputLatency || 0);
            const tick = async () => {
                if (!alive()) return;
                const ms = from + Math.max(0, clock.currentTime - latency - startAt) * 1000;
                if (ms >= totalMs) {
                    positionMs = totalMs;
                    ended = true;
                    stop();
                    return;
                }
                positionMs = ms;
                source.retire(ms);
                let pictures;
                try {
                    pictures = await gather(project, ms, (layer, clip, at) => source.frame(layer, clip, at));
                } catch (e) {
                    if (alive()) {
                        ended = true;
                        stop();
                    }
                    return;
                }
                if (!alive()) return;
                show(pictures, ms);
                requestAnimationFrame(tick);
            };
            requestAnimationFrame(tick);
        },
        pause() {
            if (!playing) return;
            stop();
            still();
        },
        position() {
            return positionMs;
        },
        ended() {
            return ended;
        },
        dispose() {
            stop();
            observer.disconnect();
            stills?.close();
            stills = null;
            audio?.close();
            audio = null;
        },
    };
}

export async function peaks(file, perSecond, maxMs) {
    const input = openInput(file);
    try {
        const track = await input.getPrimaryAudioTrack();
        if (!track) return new Float32Array(0);
        const seconds = Math.min(maxMs / 1000, await input.computeDuration());
        const out = new Float32Array(Math.ceil(seconds * perSecond));
        for await (const { buffer, timestamp } of new AudioBufferSink(track).buffers(0, seconds)) {
            const bucket = buffer.sampleRate / perSecond;
            const first = Math.round(timestamp * buffer.sampleRate);
            for (let c = 0; c < buffer.numberOfChannels; c++) {
                const data = buffer.getChannelData(c);
                for (let i = 0; i < data.length; i++) {
                    const k = Math.floor((first + i) / bucket);
                    const v = Math.abs(data[i]);
                    if (k < out.length && v > out[k]) out[k] = v;
                }
            }
        }
        return out;
    } finally {
        input.dispose();
    }
}

// 5.1 folds down the way the Android decoder does it: front + 0.707 × (centre + surround), LFE dropped
function interleave(buffers, channels, maxFrames) {
    const frames = Math.min(maxFrames, buffers.reduce((sum, b) => sum + b.length, 0));
    const out = new Int16Array(frames * channels);
    let o = 0;
    for (const buffer of buffers) {
        const data = [];
        for (let c = 0; c < buffer.numberOfChannels; c++) data.push(buffer.getChannelData(c));
        const surround = data.length === 6 && channels === 2;
        for (let i = 0; i < buffer.length && o < out.length; i++) {
            for (let c = 0; c < channels; c++) {
                const sample = surround ? (data[c][i] + 0.707 * data[2][i] + 0.707 * data[4 + c][i]) / 2.414 : data[Math.min(c, data.length - 1)][i];
                const v = Math.max(-1, Math.min(1, sample));
                out[o++] = v < 0 ? v * 32768 : v * 32767;
            }
        }
    }
    return out;
}

// Mediabunny first, it reads any container. Web Audio where WebCodecs has no audio decoder
export async function decodeAudio(file, maxMs) {
    try {
        const input = openInput(file);
        try {
            const track = await input.getPrimaryAudioTrack();
            if (!track) throw new Error('no audio stream');
            const rate = await track.getSampleRate();
            const channels = Math.min(2, await track.getNumberOfChannels());
            const limit = Math.ceil(maxMs / 1000 * rate);
            const buffers = [];
            let frames = 0;
            for await (const wrapped of new AudioBufferSink(track).buffers(0, maxMs / 1000)) {
                buffers.push(wrapped.buffer);
                frames += wrapped.buffer.length;
                if (frames >= limit) break;
            }
            return { sampleRate: rate, channels, samples: interleave(buffers, channels, limit) };
        } finally {
            input.dispose();
        }
    } catch (e) {
        const context = new OfflineAudioContext(1, 1, 48000);
        const buffer = await context.decodeAudioData(await file.arrayBuffer());
        const channels = Math.min(2, buffer.numberOfChannels);
        return { sampleRate: buffer.sampleRate, channels, samples: interleave([buffer], channels, Math.ceil(maxMs / 1000 * buffer.sampleRate)) };
    }
}

export async function openAudioEncoder(sampleRate, channels, formatName, bitrate) {
    const output = new Output({ format: outputFormat(formatName), target: new BufferTarget() });
    const source = new AudioBufferSource({ codec: await audioCodec(formatName, bitrate), bitrate });
    output.addAudioTrack(source);
    await output.start();
    return {
        async push(samples) {
            const frames = Math.floor(samples.length / channels);
            if (frames === 0) return;
            const part = new AudioBuffer({ length: frames, numberOfChannels: channels, sampleRate });
            for (let c = 0; c < channels; c++) {
                const data = new Float32Array(frames);
                for (let i = 0; i < frames; i++) data[i] = samples[i * channels + c] / 32768;
                part.copyToChannel(data, c);
            }
            await source.add(part);
        },
        async finish() {
            await output.finalize();
            return new Blob([output.target.buffer], { type: MIME[formatName] });
        },
        cancel() {
            output.cancel();
        },
    };
}

export function supportsEditing() {
    return typeof VideoEncoder !== 'undefined' && typeof OffscreenCanvas !== 'undefined';
}

export function download(blob, name) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
}

export async function blobBytes(blob) {
    return new Int8Array(await blob.arrayBuffer());
}
