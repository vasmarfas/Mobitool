package com.vasmarfas.card.core

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

object PermissionBridge {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null
    val requested = mutableSetOf<String>()

    fun attach(launcher: ActivityResultLauncher<Array<String>>) {
        this.launcher = launcher
    }

    fun detach(launcher: ActivityResultLauncher<Array<String>>) {
        if (this.launcher === launcher) this.launcher = null
    }

    fun onResult(result: Map<String, Boolean>) {
        pending?.complete(result)
        pending = null
    }

    suspend fun request(permissions: Array<String>): Map<String, Boolean> {
        val active = launcher ?: return permissions.associateWith { false }
        pending?.cancel()
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pending = deferred
        requested += permissions
        active.launch(permissions)
        return deferred.await()
    }
}

private fun manifestPermissions(permission: AppPermission): Array<String> = when (permission) {
    AppPermission.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    AppPermission.ACTIVITY_RECOGNITION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()
    AppPermission.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
    AppPermission.CAMERA -> arrayOf(Manifest.permission.CAMERA)
}

actual fun hasPermission(permission: AppPermission): Boolean {
    val context = AppContextHolder.context
    val required = manifestPermissions(permission)
    if (required.isEmpty()) return true
    return required.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

actual suspend fun ensurePermission(permission: AppPermission): Boolean {
    if (hasPermission(permission)) return true
    val required = manifestPermissions(permission)
    if (required.isEmpty()) return true
    val result = PermissionBridge.request(required)
    return result.values.any { it }
}

actual fun permissionBlocked(permission: AppPermission): Boolean {
    val activity = ActivityHolder.activity ?: return false
    val required = manifestPermissions(permission)
    return !hasPermission(permission) && required.any { it in PermissionBridge.requested } &&
        required.none { activity.shouldShowRequestPermissionRationale(it) }
}

actual fun openAppSettings() {
    val context = AppContextHolder.context
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
