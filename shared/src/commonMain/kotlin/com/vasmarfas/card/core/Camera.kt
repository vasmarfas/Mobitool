package com.vasmarfas.card.core

import io.github.vinceglb.filekit.PlatformFile

expect val photoCapture: Boolean

expect suspend fun takePhoto(): PlatformFile?

// only for what takePhoto returned, it deletes the file for good
expect fun discardPhoto(file: PlatformFile)
