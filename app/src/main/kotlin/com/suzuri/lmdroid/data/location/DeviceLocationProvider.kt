package com.suzuri.lmdroid.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** [address] is a best-effort reverse-geocoded line (e.g. "東京都千代田区...") — null when offline or unsupported; the coordinates alone are still useful to the model in that case. */
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
)

/**
 * Fetches the device's current location for the "get_current_location" tool (see
 * ConversationRepository) — plain android.location APIs rather than Google Play Services'
 * FusedLocationProviderClient, since this project doesn't otherwise depend on Play Services.
 * Returns null (never throws) whenever a location genuinely can't be obtained: permission not
 * granted, no provider enabled, or no fix within [LOCATION_TIMEOUT_MS] (e.g. indoors with GPS
 * only and no network fallback) — the caller feeds that back to the model as "unavailable" so the
 * reply doesn't fail outright over something as inherently flaky as a location fix.
 */
class DeviceLocationProvider(private val context: Context) {

    suspend fun getCurrentLocation(): DeviceLocation? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "getCurrentLocation: no location permission granted")
            return null
        }
        val location = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { requestLocation() }
        if (location == null) {
            Log.w(TAG, "getCurrentLocation: no fix obtained (disabled provider, or timed out)")
            return null
        }
        return DeviceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            address = reverseGeocode(location.latitude, location.longitude),
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION") // requestSingleUpdate has no replacement usable down to minSdk 26 (getCurrentLocation() is API 30+).
    private suspend fun requestLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.w(TAG, "requestLocation: no LocationManager system service")
            return null
        }
        val provider = bestProvider(locationManager)
        if (provider == null) {
            Log.w(TAG, "requestLocation: no usable provider is enabled (is Location turned on in system settings?)")
            return null
        }
        Log.d(TAG, "requestLocation: requesting a single update from $provider")

        return suspendCancellableCoroutine { continuation ->
            val listener = LocationListener { location ->
                Log.d(TAG, "requestLocation: got a fix from $provider")
                if (continuation.isActive) continuation.resume(location)
            }
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Permission was checked just above, but could theoretically be revoked in the
                // gap before this call actually reaches the system service.
                Log.w(TAG, "requestSingleUpdate denied", e)
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
        }
    }

    /**
     * Prefers GPS for precision, falling back to network-based positioning (faster indoors/
     * without a clear sky view) — whichever the user actually has enabled. GPS_PROVIDER requires
     * ACCESS_FINE_LOCATION specifically: on Android 12+, choosing "Approximate" in the permission
     * dialog grants only ACCESS_COARSE_LOCATION even though fine was requested, and asking
     * LocationManager for GPS_PROVIDER in that state throws SecurityException rather than just
     * being less precise — this was silently swallowing every request down to "unavailable" for
     * anyone who picked approximate.
     */
    private fun bestProvider(locationManager: LocationManager): String? {
        val enabledProviders = locationManager.getProviders(true)
        Log.d(TAG, "bestProvider: enabled=$enabledProviders, hasFine=$hasFineLocationPermission")
        if (hasFineLocationPermission && enabledProviders.contains(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER
        }
        return enabledProviders.firstOrNull { it == LocationManager.NETWORK_PROVIDER }
    }

    private val hasFineLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION") // The synchronous overload is deprecated since API 33; the callback-based replacement needs API 33+, below this project's minSdk.
    private suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.onFailure { e -> Log.w(TAG, "Reverse geocoding failed", e) }.getOrNull()
    }

    private companion object {
        const val TAG = "DeviceLocationProvider"
        const val LOCATION_TIMEOUT_MS = 10_000L
    }
}
