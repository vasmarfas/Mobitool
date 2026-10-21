package com.vasmarfas.card.core

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// without precise location and location services on, a scan comes back empty rather than failing
@RequiresPermission(Manifest.permission.ACCESS_WIFI_STATE)
actual suspend fun scanWifi(): WifiScan {
    val context = AppContextHolder.context
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return WifiScan(emptyList(), false, WifiProblem.NO_PERMISSION)
    }
    val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (!LocationManagerCompat.isLocationEnabled(location)) return WifiScan(emptyList(), false, WifiProblem.LOCATION_OFF)
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val fresh = requestScan(context, wifi)
    @Suppress("DEPRECATION")
    val connected = wifi.connectionInfo?.bssid
    val points = wifi.scanResults.map { it.toAccessPoint(it.BSSID.equals(connected, ignoreCase = true)) }
    return WifiScan(points, fresh, if (points.isEmpty() && !wifi.isWifiEnabled) WifiProblem.WIFI_OFF else null)
}

// Android 11 moved the flag out of Settings.Global into the Wi-Fi service's own store, the key there stays empty
@RequiresPermission(Manifest.permission.ACCESS_WIFI_STATE)
actual fun wifiScanLimit(): ScanLimit? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val context = AppContextHolder.context
    val resolver = context.contentResolver
    val throttled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isScanThrottleEnabled
    } else {
        Settings.Global.getInt(resolver, "wifi_scan_throttle_enabled", 1) != 0
    }
    return when {
        !throttled -> ScanLimit.OFF
        Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0 -> ScanLimit.HIDDEN
        else -> ScanLimit.ON
    }
}

@RequiresPermission(Manifest.permission.ACCESS_WIFI_STATE)
actual fun openScanLimitSettings() {
    val action = if (wifiScanLimit() == ScanLimit.HIDDEN) Settings.ACTION_DEVICE_INFO_SETTINGS else Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
    runCatching { AppContextHolder.context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private suspend fun requestScan(context: Context, wifi: WifiManager): Boolean {
    val done = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            done.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    return try {
        @Suppress("DEPRECATION")
        wifi.startScan() && withTimeoutOrNull(10_000) { done.await() } == true
    } finally {
        context.unregisterReceiver(receiver)
    }
}

private fun ScanResult.toAccessPoint(connected: Boolean): WifiAccessPoint {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Wifi.elementFeatures(informationElements.map { element ->
            val buffer = element.bytes.duplicate()
            WifiElement(element.id, element.idExt, ByteArray(buffer.remaining()).also { buffer.get(it) })
        })
    } else {
        WifiElementInfo()
    }
    val width = when (channelWidth) {
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        ScanResult.CHANNEL_WIDTH_320MHZ -> 320
        else -> 20
    }
    val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        when (wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "802.11n"
            ScanResult.WIFI_STANDARD_11AC -> "802.11ac"
            ScanResult.WIFI_STANDARD_11AX -> "802.11ax"
            ScanResult.WIFI_STANDARD_11AD -> "802.11ad"
            ScanResult.WIFI_STANDARD_11BE -> "802.11be"
            else -> ""
        }
    } else {
        ""
    }
    val extra = listOfNotNull(
        "WPS".takeIf { "[WPS]" in capabilities },
        "802.11r".takeIf { "FT/" in capabilities },
        "802.11mc".takeIf { is80211mcResponder },
    )
    @Suppress("DEPRECATION")
    val name = SSID.orEmpty()
    return WifiAccessPoint(
        ssid = name,
        bssid = BSSID.orEmpty().uppercase(),
        rssi = level,
        frequency = frequency,
        width = width,
        centerFrequency = if (width > 20 && centerFreq0 > 0) centerFreq0 else frequency,
        security = Wifi.securityOf(capabilities),
        standard = standard,
        stations = info.stations,
        utilization = info.utilization,
        streams = info.streams,
        country = info.country,
        features = info.features + extra,
        connected = connected,
    )
}
