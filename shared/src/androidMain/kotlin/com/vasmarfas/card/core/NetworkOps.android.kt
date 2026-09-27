package com.vasmarfas.card.core

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
actual suspend fun wifiDetails(): Map<String, String> {
    val context = AppContextHolder.context
    val result = LinkedHashMap<String, String>()
    runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching
        val caps = cm.getNetworkCapabilities(network)
        val link: LinkProperties? = cm.getLinkProperties(network)
        val transport = when {
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        result["Transport"] = transport
        caps?.let {
            result["Downstream"] = (it.linkDownstreamBandwidthKbps / 1000).toString()
            result["Upstream"] = (it.linkUpstreamBandwidthKbps / 1000).toString()
            result["Metered"] = (!it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).toString()
            result["Validated"] = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString()
        }
        link?.let {
            result["Interface"] = it.interfaceName ?: ""
            result["Addresses"] = it.linkAddresses.joinToString(", ") { a -> a.toString() }
            result["DNS"] = it.dnsServers.joinToString(", ") { d -> d.hostAddress ?: "" }
            result["Gateway"] = it.routes.firstOrNull { r -> r.isDefaultRoute }?.gateway?.hostAddress ?: ""
            result["Domains"] = it.domains ?: ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                result["MTU"] = it.mtu.toString()
            }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (transport == "Wi-Fi") wifiWithLocation(cm) ?: caps?.transportInfo as? WifiInfo else null
        } else {
            @Suppress("DEPRECATION")
            wifi?.connectionInfo
        }
        info?.let {
            result["SSID"] = it.ssid?.trim('"') ?: ""
            result["BSSID"] = it.bssid ?: ""
            result["RSSI"] = it.rssi.toString()
            result["Link speed"] = it.linkSpeed.toString()
            result["Frequency"] = it.frequency.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result["Wi-Fi standard"] = when (it.wifiStandard) {
                    ScanResult.WIFI_STANDARD_11AX -> "802.11ax (Wi-Fi 6)"
                    ScanResult.WIFI_STANDARD_11AC -> "802.11ac (Wi-Fi 5)"
                    ScanResult.WIFI_STANDARD_11N -> "802.11n (Wi-Fi 4)"
                    else -> it.wifiStandard.toString()
                }
            }
        }
        wifi?.let {
            result["Wi-Fi enabled"] = it.isWifiEnabled.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result["5 GHz supported"] = it.is5GHzBandSupported.toString()
                result["6 GHz supported"] = it.is6GHzBandSupported.toString()
            }
        }
    }
    return result
}

@RequiresApi(Build.VERSION_CODES.S)
@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
private suspend fun wifiWithLocation(cm: ConnectivityManager): WifiInfo? {
    val found = CompletableDeferred<WifiInfo?>()
    val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            found.complete(networkCapabilities.transportInfo as? WifiInfo)
        }
    }
    cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    return try {
        withTimeoutOrNull(1_000) { found.await() }
    } finally {
        cm.unregisterNetworkCallback(callback)
    }
}
