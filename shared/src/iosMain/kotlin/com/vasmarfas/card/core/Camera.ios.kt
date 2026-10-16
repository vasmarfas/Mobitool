package com.vasmarfas.card.core

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openCameraPicker
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerSourceType

actual val photoCapture: Boolean
    get() = UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

actual suspend fun takePhoto(): PlatformFile? = FileKit.openCameraPicker()
