package com.vasmarfas.card.core

import io.github.vinceglb.filekit.PlatformFile

actual fun discardPhoto(file: PlatformFile) = MediaResult(file).discard()
