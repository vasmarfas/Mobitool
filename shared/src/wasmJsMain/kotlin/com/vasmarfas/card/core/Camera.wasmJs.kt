package com.vasmarfas.card.core

import io.github.vinceglb.filekit.PlatformFile

actual val photoCapture: Boolean = false

actual suspend fun takePhoto(): PlatformFile? = null
