import Tesseract from './vendor/tesseract/tesseract.esm.min.js';

const BASE = new URL('./vendor/tesseract/', import.meta.url).href;
let worker = null;
let workerLanguages = '';

async function workerFor(languages, langPath) {
    if (worker && workerLanguages === languages) return worker;
    if (worker) await worker.terminate();
    worker = null;
    worker = await Tesseract.createWorker(languages, 1, {
        workerPath: BASE + 'worker.min.js',
        corePath: BASE + 'core/',
        langPath: new URL(langPath, document.baseURI).href,
        gzip: false,
        cacheMethod: 'none',
    });
    workerLanguages = languages;
    return worker;
}

export async function recognize(bytes, languages, langPath) {
    const reader = await workerFor(languages, langPath);
    const { data } = await reader.recognize(new Blob([bytes], { type: 'image/png' }), {}, { text: false, blocks: true });
    const lines = [];
    for (const block of data.blocks || []) {
        for (const paragraph of block.paragraphs || []) {
            for (const line of paragraph.lines || []) {
                lines.push({
                    text: line.text.trimEnd(),
                    confidence: line.confidence,
                    left: line.bbox.x0,
                    top: line.bbox.y0,
                    right: line.bbox.x1,
                    bottom: line.bbox.y1,
                });
            }
        }
    }
    return lines;
}
