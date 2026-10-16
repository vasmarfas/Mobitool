package com.vasmarfas.card.core

import io.github.vinceglb.filekit.PlatformFile

expect val photoCapture: Boolean

expect suspend fun takePhoto(): PlatformFile?
