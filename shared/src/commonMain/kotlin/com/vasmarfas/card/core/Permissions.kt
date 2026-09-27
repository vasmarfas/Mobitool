package com.vasmarfas.card.core

enum class AppPermission { LOCATION, ACTIVITY_RECOGNITION, MICROPHONE, CAMERA }

expect suspend fun ensurePermission(permission: AppPermission): Boolean

expect fun hasPermission(permission: AppPermission): Boolean

expect fun permissionBlocked(permission: AppPermission): Boolean

expect fun openAppSettings()
