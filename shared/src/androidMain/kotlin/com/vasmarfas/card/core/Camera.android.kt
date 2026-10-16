package com.vasmarfas.card.core

import android.content.pm.PackageManager
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openCameraPicker

actual val photoCapture: Boolean
    get() = AppContextHolder.context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

actual suspend fun takePhoto(): PlatformFile? = FileKit.openCameraPicker()
