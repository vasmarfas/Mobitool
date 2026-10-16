package com.vasmarfas.card.core

import java.io.File

internal actual fun ocrDataDir(): File = File(System.getProperty("java.io.tmpdir"), "mobitool-ocr")
