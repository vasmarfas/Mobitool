package com.vasmarfas.card.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.shareFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.milliseconds

private const val FOLDER = "Mobitool"

actual fun incomingFile(name: String): IncomingFile {
    val context = AppContextHolder.context
    val safe = safeFileName(name)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return receivedFile(Path(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.path, FOLDER), safe)
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, safe)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        put(MediaStore.Downloads.IS_PENDING, 1)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase())?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $safe")
    val stream = resolver.openOutputStream(uri) ?: error("MediaStore gave no stream for $safe")
    return IncomingFile(
        sink = stream.asSink(),
        keep = {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            PlatformFile(uri)
        },
        drop = { runCatching { resolver.delete(uri, null, null) } },
    )
}

actual fun incomingFolder(): String? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "Android/data/${AppContextHolder.context.packageName}/files/Download/$FOLDER" else "Download/$FOLDER"

actual fun deviceName(): String = Settings.Global.getString(AppContextHolder.context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

actual suspend fun shareFiles(files: List<PlatformFile>): Boolean = runCatching { FileKit.shareFile(files) }.isSuccess

// Wi-Fi drivers drop multicast to save power unless an app holds this lock
actual fun joinMulticast(group: String, port: Int): MulticastGroup? {
    val wifi = AppContextHolder.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock(FOLDER).apply {
        setReferenceCounted(false)
        acquire()
    }
    return runCatching { JavaMulticast(group, port) { lock.release() } }.getOrElse {
        lock.release()
        null
    }
}

actual suspend fun shareOverBluetooth(files: List<PlatformFile>): Boolean {
    val context = AppContextHolder.context
    val uris = ArrayList(files.map { it.toAndroidUri() })
    if (uris.isEmpty()) return false
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val bluetooth = context.packageManager.queryIntentActivities(intent, 0)
        .firstOrNull { it.activityInfo.packageName.contains("bluetooth", ignoreCase = true) } ?: return false
    intent.setClassName(bluetooth.activityInfo.packageName, bluetooth.activityInfo.name)
    return runCatching { context.startActivity(intent) }.isSuccess
}

@SuppressLint("MissingPermission")
actual suspend fun startHotspot(): Hotspot? {
    val context = AppContextHolder.context
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
        PermissionBridge.request(arrayOf(permission))[permission] != true
    ) {
        return null
    }
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val started = CompletableDeferred<WifiManager.LocalOnlyHotspotReservation?>()
    runCatching {
        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                if (!started.complete(reservation)) reservation.close()
            }

            override fun onFailed(reason: Int) {
                started.complete(null)
            }
        }, Handler(Looper.getMainLooper()))
    }.onFailure { return null }
    val reservation = withTimeoutOrNull(15_000.milliseconds) { started.await() } ?: return null.also { started.cancel() }
    @Suppress("DEPRECATION")
    val (ssid, password) = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> reservation.softApConfiguration.let { it.wifiSsid?.toString() to it.passphrase }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> reservation.softApConfiguration.let { it.ssid to it.passphrase }
        else -> reservation.wifiConfiguration?.let { it.SSID to it.preSharedKey } ?: (null to null)
    }
    if (ssid == null) {
        reservation.close()
        return null
    }
    return Hotspot(ssid.removeSurrounding("\""), password.orEmpty().removeSurrounding("\""), reservation::close)
}

actual fun shareOverNfc(url: String?): Boolean {
    NfcLinkService.ndef = url?.let { NdefMessage(NdefRecord.createUri(it)).toByteArray() }
    val context = AppContextHolder.context
    return url != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) &&
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
}
