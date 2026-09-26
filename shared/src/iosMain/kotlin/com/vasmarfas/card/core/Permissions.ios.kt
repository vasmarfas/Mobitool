package com.vasmarfas.card.core

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

private class LocationAuthDelegate : NSObject(), CLLocationManagerDelegateProtocol {
    var onDecided: ((Boolean) -> Unit)? = null

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
        onDecided?.invoke(locationGranted())
        onDecided = null
    }
}

// the answer to the prompt comes through the delegate, which the manager holds weakly, so both live here
private val locationAuth = LocationAuthDelegate()
private val locationManager by lazy { CLLocationManager().apply { delegate = locationAuth } }

private fun locationGranted(): Boolean = locationManager.authorizationStatus.let {
    it == kCLAuthorizationStatusAuthorizedWhenInUse || it == kCLAuthorizationStatusAuthorizedAlways
}

actual suspend fun ensurePermission(permission: AppPermission): Boolean = when (permission) {
    AppPermission.MICROPHONE -> when (AVAudioApplication.sharedInstance().recordPermission) {
        AVAudioApplicationRecordPermissionGranted -> true
        AVAudioApplicationRecordPermissionDenied -> false
        else -> suspendCancellableCoroutine { continuation ->
            AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> continuation.resume(granted) }
        }
    }
    AppPermission.CAMERA -> when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> true
        AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted -> continuation.resume(granted) }
        }
        else -> false
    }
    AppPermission.LOCATION -> when (locationManager.authorizationStatus) {
        kCLAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { continuation ->
            locationAuth.onDecided = { continuation.resume(it) }
            locationManager.requestWhenInUseAuthorization()
        }
        else -> locationGranted()
    }
    else -> true
}

actual fun hasPermission(permission: AppPermission): Boolean = when (permission) {
    AppPermission.MICROPHONE -> AVAudioApplication.sharedInstance().recordPermission == AVAudioApplicationRecordPermissionGranted
    AppPermission.CAMERA -> AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
    AppPermission.LOCATION -> locationGranted()
    else -> true
}

actual fun permissionBlocked(permission: AppPermission): Boolean = when (permission) {
    AppPermission.MICROPHONE -> AVAudioApplication.sharedInstance().recordPermission == AVAudioApplicationRecordPermissionDenied
    AppPermission.CAMERA -> AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo).let {
        it == AVAuthorizationStatusDenied || it == AVAuthorizationStatusRestricted
    }
    // denied also covers location services switched off for the whole device
    AppPermission.LOCATION -> locationManager.authorizationStatus.let {
        it == kCLAuthorizationStatusDenied || it == kCLAuthorizationStatusRestricted
    }
    else -> false
}

actual fun openAppSettings() {
    NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
}
