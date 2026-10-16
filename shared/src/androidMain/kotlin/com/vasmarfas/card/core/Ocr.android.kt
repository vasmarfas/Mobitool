package com.vasmarfas.card.core

import java.io.File

internal actual fun ocrDataDir(): File = AppContextHolder.context.noBackupFilesDir
