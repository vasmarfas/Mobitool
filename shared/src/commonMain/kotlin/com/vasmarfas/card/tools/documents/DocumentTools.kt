package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.documents.editor.pdfEditorTool

val documentTools: List<Tool> = listOf(
    pdfEditorTool,
    documentConverterTool,
    docScannerTool,
    photoToTextTool,
    invoiceTool,
    imagesToPdfTool,
    mergePdfTool,
    compressPdfTool,
    pdfPagesTool,
    pdfToImagesTool,
    pdfToTextTool,
    unlockPdfTool,
    zipArchiveTool,
)
